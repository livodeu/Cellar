/*
 * EpubAnalyzer.java
 * Copyright (c) livodeu 2021.
 * This source code is subject to the license to be found in the file LICENSE.
 */

package net.cellar.supp;

import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.cellar.BuildConfig;
import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.model.FileHeader;

import org.xml.sax.InputSource;
import org.xml.sax.helpers.DefaultHandler;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

/**
 * Retrieves some metadata from epub files.
 */
public class EpubAnalyzer {

    @NonNull
    public static EpubAnalyzer analyze(@NonNull File file) {
        final EpubAnalyzer epubAnalyzer = new EpubAnalyzer();
        File opf = null;
        try {
            final ZipFile zipFile = new ZipFile(file);
            epubAnalyzer.valid = zipFile.isValidZipFile();
            final List<FileHeader> headers = zipFile.getFileHeaders();
            for (FileHeader header : headers) {
                String name = header.getFileName();
                if (name.toLowerCase(java.util.Locale.US).endsWith(".opf")) {
                    String dir = System.getProperty("java.io.tmpdir");
                    zipFile.extractFile(header, dir);
                    opf = new File(dir, name);
                    OpfWrangler opfWrangler = new OpfWrangler().wrangle(opf);
                    epubAnalyzer.title = opfWrangler.title;
                    epubAnalyzer.creator = opfWrangler.creator;
                    epubAnalyzer.language = opfWrangler.language;
                    epubAnalyzer.description = opfWrangler.description;
                    if (TextUtils.isEmpty(epubAnalyzer.description)) {
                        String zipFileComment = zipFile.getComment();
                        if (!TextUtils.isEmpty(zipFileComment)) epubAnalyzer.description = zipFileComment;
                    }
                    break;
                }
            }
        } catch (Throwable e) {
            epubAnalyzer.valid = false;
            if (BuildConfig.DEBUG) Log.e(EpubAnalyzer.class.getSimpleName(), e.toString());
        }
        Util.deleteFile(opf);
        return epubAnalyzer;
    }

    private boolean valid;
    private String creator, description, language, title;

    private EpubAnalyzer() {
        super();
    }

    @Nullable
    public String getCreator() {
        return creator;
    }

    @Nullable
    public String getDescription() {
        return description;
    }

    @Nullable
    public String getLanguage() {
        return language;
    }

    @Nullable
    public String getTitle() {
        return title;
    }

    public boolean isValid() {
        return valid;
    }

    /**
     * Looks into an opf file to retrieve some metadata.
     * See <a href="https://en.wikipedia.org/wiki/EPUB#Open_Packaging_Format_2.0.1">https://en.wikipedia.org/wiki/EPUB#Open_Packaging_Format_2.0.1</a>
     */
    private static class OpfWrangler extends DefaultHandler {
        private static SAXParserFactory SAXPARSER_FACTORY = null;
        String title;
        String creator;
        String language;
        String description;
        private StringBuilder builder;

        /** {@inheritDoc} */
        @Override
        public final void characters(final char[] ch, int start, int length) {
            // limit the length to sensible values to avoid nasty input data
            if (this.builder.length() > 1024) return;
            //
            this.builder.append(ch, start, length);
        }

        /** {@inheritDoc} */
        @Override
        public void endElement(String uri, String localName, String qName) {
            if ("title".equals(localName)) {
                this.title = builder.toString().trim();
            } else if ("creator".equals(localName)) {
                if (this.creator != null) this.creator = this.creator + ", " + builder.toString().trim();
                else this.creator = builder.toString().trim();
            } else if ("language".equals(localName)) {
                this.language = builder.toString().trim();
            } else if ("description".equals(localName)) {
                this.description = builder.toString().trim();
            }
            // Unfortunately there is no meta data for the page count
            builder.setLength(0);
        }

        /** {@inheritDoc} */
        @Override
        public void startDocument() {
            this.builder = new StringBuilder(128);
        }

        OpfWrangler wrangle(@NonNull File file) {
            if (SAXPARSER_FACTORY == null) SAXPARSER_FACTORY = SAXParserFactory.newInstance();
            SAXParser parser;
            InputStreamReader reader = null;
            try {
                parser = SAXPARSER_FACTORY.newSAXParser();
                reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8);
                parser.parse(new InputSource(reader), this);
            } catch (Throwable e) {
                if (BuildConfig.DEBUG) Log.e(OpfWrangler.class.getSimpleName(), e.toString());
            } finally {
                Util.close(reader);
            }
            return this;
        }

    }
}
