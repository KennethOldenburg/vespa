// Copyright 2018 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.container.handler;

import com.yahoo.vespa.test.file.TestFileSystem;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;

public class LogReaderTest {

    private final FileSystem fileSystem = TestFileSystem.create();
    private final Path logDirectory = fileSystem.getPath("/opt/vespa/logs");

    private static final String logv11 = "3600.2\t17491290-v6-1.ostk.bm2.prod.ne1.yahoo.com\t5480\tcontainer\tstdout\tinfo\tfourth\n";
    private static final String logv   = "90000.1\t17491290-v6-1.ostk.bm2.prod.ne1.yahoo.com\t5480\tcontainer\tstdout\tinfo\tlast\n";
    private static final String log100 = "0.2\t17491290-v6-1.ostk.bm2.prod.ne1.yahoo.com\t5480\tcontainer\tstdout\tinfo\tsecond\n";
    private static final String log101 = "0.1\t17491290-v6-1.ostk.bm2.prod.ne1.yahoo.com\t5480\tcontainer\tstdout\tinfo\tERROR: Bundle canary-application [71] Unable to get module class path. (java.lang.NullPointerException)\n";
    private static final String log110 = "3600.1\t17491290-v6-1.ostk.bm2.prod.ne1.yahoo.com\t5480\tcontainer\tstderr\twarning\tthird\n";
    private static final String log200 = "86400.1\t17491290-v6-1.ostk.bm2.prod.ne1.yahoo.com\t5480\tcontainer\tstderr\twarning\tjava.lang.NullPointerException\\n\\tat org.apache.felix.framework.BundleRevisionImpl.calculateContentPath(BundleRevisionImpl.java:438)\\n\\tat org.apache.felix.framework.BundleRevisionImpl.initializeContentPath(BundleRevisionImpl.java:371)\n";

    @Before
    public void setup() throws IOException {
        // Log archive paths and file names indicate what hour they contain logs for, with the start of that hour.
        // Multiple entries may exist for each hour.
        Files.createDirectories(logDirectory.resolve("1970/01/01"));
        Files.write(logDirectory.resolve("1970/01/01/00-0.gz"), compress(log100));
        Files.write(logDirectory.resolve("1970/01/01/00-1"), log101.getBytes(UTF_8));
        Files.write(logDirectory.resolve("1970/01/01/01-0.gz"), compress(log110));

        Files.createDirectories(logDirectory.resolve("1970/01/02"));
        Files.write(logDirectory.resolve("1970/01/02/00-0"), log200.getBytes(UTF_8));

        // Vespa log file names are the second-truncated timestamp of the last entry.
        // The current log file has no timestamp suffix.
        Files.write(logDirectory.resolve("vespa.log-1970-01-01.01-00-00"), logv11.getBytes(UTF_8));
        Files.write(logDirectory.resolve("vespa.log"), logv.getBytes(UTF_8));
    }

    @Test
    public void testThatLogsOutsideRangeAreExcluded() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        LogReader logReader = new LogReader(logDirectory, Pattern.compile(".*"));
        logReader.writeLogs(baos, Instant.ofEpochMilli(150), Instant.ofEpochMilli(3601050));

        assertEquals(log100 + logv11 + log110, decompress(baos.toByteArray()));
    }

    @Test
    public void testThatLogsNotMatchingRegexAreExcluded() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        LogReader logReader = new LogReader(logDirectory, Pattern.compile(".*-1.*"));
        logReader.writeLogs(baos, Instant.EPOCH, Instant.EPOCH.plus(Duration.ofDays(2)));

        assertEquals(log101 + logv11, decompress(baos.toByteArray()));
    }

    @Test
    public void testZippedStreaming() throws IOException {
        ByteArrayOutputStream zippedBaos = new ByteArrayOutputStream();
        LogReader logReader = new LogReader(logDirectory, Pattern.compile(".*"));
        logReader.writeLogs(zippedBaos, Instant.EPOCH, Instant.EPOCH.plus(Duration.ofDays(2)));

        assertEquals(log100 + log101 + logv11 + log110 + log200 + logv, decompress(zippedBaos.toByteArray()));
    }

    private byte[] compress(String input) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        OutputStream zip = new GZIPOutputStream(baos);
        zip.write(input.getBytes());
        zip.close();
        return baos.toByteArray();
    }

    private String decompress(byte[] input) throws IOException {
        if (input.length == 0) return "";
        byte[] decompressed = new GZIPInputStream(new ByteArrayInputStream(input)).readAllBytes();
        return new String(decompressed);
    }

}
