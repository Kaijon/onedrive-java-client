package com.wouterbreukink.onedrive.sync;

import com.wouterbreukink.onedrive.Utils;
import com.wouterbreukink.onedrive.client.OneDriveAPI;
import com.wouterbreukink.onedrive.client.resources.Item;
import jersey.repackaged.com.google.common.base.Preconditions;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Date;
import java.util.concurrent.TimeUnit;

import static com.wouterbreukink.onedrive.CommandLineOpts.getCommandLineOpts;

public class CheckFileTask extends Task {

    private static final Logger log = LogManager.getLogger(CheckFileTask.class.getName());

    private final OneDriveAPI api;
    private final Item remoteFile;
    private final File localFile;

    public CheckFileTask(TaskQueue queue, OneDriveAPI api, Item remoteFile, File localFile) {

        super(queue);

        this.api = Preconditions.checkNotNull(api);
        this.remoteFile = Preconditions.checkNotNull(remoteFile);
        this.localFile = Preconditions.checkNotNull(localFile);
    }

    public int priority() {
        return 50;
    }

    @Override
    public String toString() {
        return "Check file " + remoteFile.getFullName();
    }

    @Override
    protected void taskBody() {

        try {
            BasicFileAttributes attr = Files.readAttributes(localFile.toPath(), BasicFileAttributes.class);

            Date localCreatedDate = new Date(attr.creationTime().to(TimeUnit.SECONDS) * 1000);
            Date localModifiedDate = new Date(attr.lastModifiedTime().to(TimeUnit.SECONDS) * 1000);

            boolean sizeMatches = remoteFile.getSize() == localFile.length();
            boolean createdMatches = remoteFile.getFileSystemInfo().getCreatedDateTime().equals(localCreatedDate);
            boolean modifiedMatches = remoteFile.getFileSystemInfo().getLastModifiedDateTime().equals(localModifiedDate);

            if (!getCommandLineOpts().useHash() && sizeMatches && createdMatches && modifiedMatches) {
                // Close enough!
                return;
            }

            int maxSizeKb = getCommandLineOpts().getMaxSizeKb();
            if (maxSizeKb > 0 && localFile.length() > maxSizeKb * 1024) {
                log.info(String.format("Skipping file %s - size is bigger than %dKB",
                        remoteFile.getFullName(),
                        maxSizeKb));
                return;
            }

            long remoteCrc = remoteFile.getFile().getHashes().getCrc32();
            long localCrc = Utils.getChecksum(localFile);

            // If the content is different
            if (remoteCrc != localCrc) {
                queue.add(new UploadFileTask(queue, api, remoteFile.getParentReference(), localFile, true));
            } else if (!createdMatches || !modifiedMatches) {
                queue.add(new UpdateFileDatesTask(queue, api, remoteFile, localCreatedDate, localModifiedDate));
            }

        } catch (IOException e) {
            log.error("Unable to compare file", e);
        }
    }
}