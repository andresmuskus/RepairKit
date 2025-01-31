/*
 * RepairKit - an all-in-one Java-based Windows repair and maintenance toolkit.
 * Copyright (C) 2024 Foulest (https://github.com/Foulest)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package net.foulest.repairkit.util;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import net.foulest.repairkit.util.config.ConfigLoader;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveTask;

/**
 * Utility class for removing junk files from the system.
 *
 * @author Foulest
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class JunkFileUtil {

    // File extensions to scan for
    private static Set<String> JUNK_FILE_EXTENSIONS = Set.of();

    // Paths to exclude from scanning
    private static Set<Path> EXCLUDED_PATHS = Set.of();

    // Time constants
    private static final long LAST_24_HOURS = Instant.now().minus(24, ChronoUnit.HOURS).toEpochMilli();

    // Analytics
    private static long totalCount;
    private static long totalSize;

    // File System Pool
    // Uses between 2 and 25% of the available threads
    private static final ForkJoinPool pool = new ForkJoinPool(Math.max(2, Runtime.getRuntime().availableProcessors() / 4));

    /**
     * Checks for junk files on the system.
     */
    @SuppressWarnings("unchecked")
    public static void removeJunkFiles() {
        // Gets the file extensions to scan for from the config file.
        try {
            ConfigLoader configLoader = new ConfigLoader(FileUtil.getConfigFile("junkfiles.json"));
            Map<String, Object> junkFilesConfig = configLoader.getConfig().get("junkFiles");

            // Returns if the config file is missing.
            if (junkFilesConfig == null) {
                return;
            }

            // Returns if the feature is disabled.
            if (junkFilesConfig.get("enabled") != null
                    && !Boolean.TRUE.equals(junkFilesConfig.get("enabled"))) {
                return;
            }

            // Gets the file extensions to scan for from the config file.
            if (junkFilesConfig.get("fileExtensions") != null
                    && !((Collection<String>) junkFilesConfig.get("fileExtensions")).isEmpty()) {
                JUNK_FILE_EXTENSIONS = Set.copyOf((Collection<String>) junkFilesConfig.get("fileExtensions"));
            }

            // Gets the paths to exclude from scanning from the config file.
            if (junkFilesConfig.get("excludedPaths") != null
                    && !((Collection<String>) junkFilesConfig.get("excludedPaths")).isEmpty()) {
                Set<Path> excludedPaths = new HashSet<>();

                for (String path : (Iterable<String>) junkFilesConfig.get("excludedPaths")) {
                    String fixedPath = path.replace("%temp%", System.getenv("TEMP"));
                    excludedPaths.add(Paths.get(fixedPath));
                }

                EXCLUDED_PATHS = Set.copyOf(excludedPaths);
            }

            // Collects data for analytics.
            long now = System.currentTimeMillis();
            totalCount = 0;
            totalSize = 0;

            List<Runnable> tasks = new ArrayList<>(List.of());

            // Empties the Recycle Bin.
            if (junkFilesConfig.get("emptyRecycleBin") != null
                    && Boolean.TRUE.equals(junkFilesConfig.get("emptyRecycleBin"))) {
                tasks.add(() -> CommandUtil.runPowerShellCommand("Clear-RecycleBin -Force", false));
            }

            // Deletes files in the Temp directory older than one day.
            if (junkFilesConfig.get("cleanUserTempFiles") != null
                    && Boolean.TRUE.equals(junkFilesConfig.get("cleanUserTempFiles"))) {
                tasks.add(() -> CommandUtil.runPowerShellCommand("Get-ChildItem -Path $env:TEMP -Recurse | Where-Object { $_.LastWriteTime -lt (Get-Date).AddDays(-1) } | Remove-Item -Recurse -Force", false));
            }

            // Deletes files in the Windows temp directory.
            if (junkFilesConfig.get("cleanSystemTempFiles") != null
                    && Boolean.TRUE.equals(junkFilesConfig.get("cleanSystemTempFiles"))) {
                tasks.add(() -> CommandUtil.runPowerShellCommand("Get-ChildItem -Path $env:windir\\Temp -Recurse | Remove-Item -Recurse -Force", false));
            }

            // Scans each drive for junk files.
            Iterable<Path> rootDirectories = FileSystems.getDefault().getRootDirectories();
            for (Path root : rootDirectories) {
                tasks.add(() -> pool.invoke(new DirectoryScanTask(root)));
            }

            // Executes tasks using TaskUtil.
            TaskUtil.executeTasks(tasks);
            DebugUtil.debug("Junk files found: " + totalCount);
            DebugUtil.debug("Total size: " + totalSize);
            DebugUtil.debug("Time taken: " + (System.currentTimeMillis() - now) + "ms");
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }

    @AllArgsConstructor
    private static class DirectoryScanTask extends RecursiveTask<Void> {

        @Serial
        private static final long serialVersionUID = 7784391839228931588L;
        private final Path directory;

        @Override
        protected @Nullable Void compute() {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
                // Create a list of tasks to execute concurrently
                List<DirectoryScanTask> tasks = new ArrayList<>();

                for (Path path : stream) {
                    if (Files.isDirectory(path)) {
                        // Ignores excluded paths.
                        if (EXCLUDED_PATHS.contains(directory)) {
                            continue;
                        }

                        // Create a new task for each subdirectory and fork it
                        DirectoryScanTask task = new DirectoryScanTask(path);
                        task.fork();
                        tasks.add(task);
                    } else {
                        if (isJunkFile(path, Files.readAttributes(path, BasicFileAttributes.class))) {
                            // Gets the file's size
                            long size = Files.size(path);

                            // Aggregates the data for analytics
                            totalCount++;
                            totalSize += size;

                            // Prints the file path before deleting it
                            DebugUtil.debug("Cleaning junk file: " + path + " (" + size + " bytes)");

                            // Deletes the file
                            try {
                                Files.delete(path);
                            } catch (IOException ignored) {
                            }
                        }
                    }
                }

                // Wait for all tasks to complete
                for (DirectoryScanTask task : tasks) {
                    task.join();
                }
            } catch (IOException ignored) {
            }
            return null;
        }

        @Serial
        private void readObject(ObjectInputStream ignored) throws IOException, ClassNotFoundException {
            throw new NotSerializableException("JunkFileUtil.DirectoryScanTask");
        }

        @Serial
        private void writeObject(ObjectOutputStream ignored) throws IOException {
            throw new NotSerializableException("JunkFileUtil.DirectoryScanTask");
        }
    }

    /**
     * Checks if the file is a junk file.
     * <p>
     * This is classified as a file that ends with one of the junk file
     * extensions, and was not accessed in the last 24 hours.
     *
     * @param file The file to check.
     * @param attrs The file's attributes.
     * @return {@code true} if the file is a junk file, otherwise {@code false}.
     */
    private static boolean isJunkFile(@NotNull Path file, BasicFileAttributes attrs) {
        // Ignores directories.
        if (file.toFile().isDirectory()) {
            return false;
        }

        // Ignores files accessed in the last 24 hours.
        if (attrs.lastAccessTime().toMillis() > LAST_24_HOURS) {
            return false;
        }

        String fileName = file.getFileName().toString().toLowerCase(Locale.ROOT);

        for (String extension : JUNK_FILE_EXTENSIONS) {
            if (fileName.equalsIgnoreCase(extension) || fileName.endsWith(extension)) {
                return true;
            }
        }
        return false;
    }
}
