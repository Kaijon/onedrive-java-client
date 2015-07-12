package com.wouterbreukink.onedrive;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.FileAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.layout.PatternLayout;

import java.io.Serializable;

public class LogUtils {

    private LogUtils() {
    }

    public static String addFileLogger(String logFile) {

        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        Configuration config = ctx.getConfiguration();

        // Default log layout
        Layout<? extends Serializable> layout =
                PatternLayout.createLayout("%d %p [%t] %m%n", null, null, null, true, true, null, null);

        // Create a new file appender for the given filename
        FileAppender appender = FileAppender.createAppender(
                logFile,
                "false",
                "false",
                "FileAppender",
                "false",
                "true",
                "true",
                null,
                layout,
                null,
                null,
                null,
                config);

        appender.start();
        ((Logger) LogManager.getRootLogger()).addAppender(appender);

        return appender.getFileName();
    }
}
