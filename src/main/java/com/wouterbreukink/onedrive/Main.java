package com.wouterbreukink.onedrive;

import com.wouterbreukink.onedrive.client.OneDriveAuth;
import com.wouterbreukink.onedrive.client.api.OneDriveItem;
import com.wouterbreukink.onedrive.client.api.OneDriveProvider;
import com.wouterbreukink.onedrive.client.resources.Drive;
import com.wouterbreukink.onedrive.fs.FileSystemProvider;
import com.wouterbreukink.onedrive.tasks.CheckTask;
import com.wouterbreukink.onedrive.tasks.Task;
import com.wouterbreukink.onedrive.tasks.TaskReporter;
import org.apache.commons.cli.ParseException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.glassfish.jersey.client.HttpUrlConnectorProvider;
import org.glassfish.jersey.jackson.JacksonFeature;
import org.glassfish.jersey.media.multipart.MultiPartFeature;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.wouterbreukink.onedrive.CommandLineOpts.getCommandLineOpts;
import static com.wouterbreukink.onedrive.LogUtils.readableFileSize;

/***
 * OneDrive Java Client
 * Copyright (C) 2015 Wouter Breukink
 * <p>
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 * <p>
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, write to the Free Software Foundation, Inc.,
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 */
public class Main {

    private static final Logger log = LogManager.getLogger(Main.class.getName());

    public static void main(String[] args) throws Exception {

        // Parse command line args
        try {
            CommandLineOpts.initialise(args);
        } catch (ParseException ex) {
            log.error("Unable to parse command line arguments - " + ex.getMessage());
            CommandLineOpts.printHelp();
            return;
        }

        if (getCommandLineOpts().help()) {
            CommandLineOpts.printHelp();
            return;
        }

        if (getCommandLineOpts().version()) {
            String version = getCommandLineOpts().getClass().getPackage().getImplementationVersion();
            log.info("onedrive-java-client version " + (version != null ? version : "DEVELOPMENT"));
            return;
        }

        // Initialise a log file (if set)
        if (getCommandLineOpts().getLogFile() != null) {
            String logFileName = LogUtils.addFileLogger(getCommandLineOpts().getLogFile());
            log.info(String.format("Writing log output to %s", logFileName));
        }

        // Initialise the client
        Client client = ClientBuilder
                .newClient()
                        //.register(new LoggingFilter(Logger.getLogger(LoggingFilter.class.getName()), false))
                .register(MultiPartFeature.class)
                .register(JacksonFeature.class);

        // Workaround to be able to submit PATCH requests
        client.property(HttpUrlConnectorProvider.SET_METHOD_WORKAROUND, true);

        System.setProperty("sun.net.http.allowRestrictedHeaders", "true");

        // Prepare the authoriser
        OneDriveAuth authoriser = new OneDriveAuth(client);

        if (getCommandLineOpts().isAuthorise()) {
            authoriser.printAuthInstructions(true);
            return;
        }

        if (getCommandLineOpts().getLocalPath() == null
                || getCommandLineOpts().getRemotePath() == null
                || getCommandLineOpts().getDirection() == null) {
            log.error("Must specify --local, --remote and --direction");
            CommandLineOpts.printHelp();
            return;
        }

        // Try initialise the authoriser
        if (!authoriser.initialise(getCommandLineOpts().getKeyFile())) {
            authoriser.printAuthInstructions(false);
            return;
        }

        // Initialise the providers
        OneDriveProvider api;
        FileSystemProvider fileSystem;
        if (getCommandLineOpts().isDryRun()) {
            log.warn("This is a dry run - no changes will be made");
            api = OneDriveProvider.FACTORY.readOnlyApi(client, authoriser);
            fileSystem = FileSystemProvider.FACTORY.readOnlyProvider();
        } else {
            api = OneDriveProvider.FACTORY.readWriteApi(client, authoriser);
            fileSystem = FileSystemProvider.FACTORY.readWriteProvider();
        }

        // Report on progress
        TaskReporter reporter = new TaskReporter();

        // Get the primary drive
        Drive primary = api.getDefaultDrive();

        // Report quotas
        log.info(String.format("Using drive with id '%s' (%s). Usage %s of %s (%.2f%%)",
                primary.getId(),
                primary.getDriveType(),
                readableFileSize(primary.getQuota().getUsed()),
                readableFileSize(primary.getQuota().getTotal()),
                ((double) primary.getQuota().getUsed() / primary.getQuota().getTotal()) * 100));

        // Check the given root folder
        OneDriveItem rootFolder = api.getPath(getCommandLineOpts().getRemotePath());

        if (!rootFolder.isDirectory()) {
            log.error(String.format("Specified root '%s' is not a folder", rootFolder.getFullName()));
            return;
        }

        log.info(String.format("Starting at root folder '%s'", rootFolder.getFullName()));

        // Start synchronisation operation at the root
        final TaskQueue queue = new TaskQueue();
        queue.add(new CheckTask(new Task.TaskOptions(queue, api, fileSystem, reporter), rootFolder, new File(getCommandLineOpts().getLocalPath())));

        // Get a bunch of threads going
        ExecutorService executorService = Executors.newFixedThreadPool(getCommandLineOpts().getThreads());

        for (int i = 0; i < getCommandLineOpts().getThreads(); i++) {
            executorService.submit(new Runnable() {
                @Override
                public void run() {
                    try {
                        //noinspection InfiniteLoopStatement
                        while (true) {
                            Task taskToRun = null;
                            try {
                                taskToRun = queue.take();
                                taskToRun.run();
                            } finally {
                                if (taskToRun != null) {
                                    queue.done(taskToRun);
                                }
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
        }

        queue.waitForCompletion();
        log.info("Synchronisation complete");
        reporter.report();

        System.exit(0);
    }
}
