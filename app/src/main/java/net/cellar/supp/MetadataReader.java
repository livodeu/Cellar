/*
 * MetadataReader.java
 * Copyright (c) livodeu 2021.
 * This source code is subject to the license to be found in the file LICENSE.
 */

package net.cellar.supp;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.exifinterface.media.ExifInterface;

import net.cellar.BuildConfig;

import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@SuppressWarnings("deprecation")
@TargetApi(Build.VERSION_CODES.Q)
public final class MetadataReader {

    /** EXIF tags considered in searches */
    public static final String[] FOR_SEARCHES = new String[]{
            ExifInterface.TAG_ARTIST, ExifInterface.TAG_BODY_SERIAL_NUMBER, ExifInterface.TAG_CAMERA_OWNER_NAME, ExifInterface.TAG_COPYRIGHT,
            ExifInterface.TAG_IMAGE_DESCRIPTION,
            ExifInterface.TAG_LENS_MAKE, ExifInterface.TAG_LENS_MODEL, ExifInterface.TAG_LENS_SERIAL_NUMBER,
            ExifInterface.TAG_MAKE, ExifInterface.TAG_MAKER_NOTE, ExifInterface.TAG_MODEL, ExifInterface.TAG_SOFTWARE,
            ExifInterface.TAG_USER_COMMENT
    };
    @SuppressLint("SimpleDateFormat")
    private static final DateFormat DATE_FORMAT = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss");
    private static final String[] DEFAULT_EXIF_TAGS = {
            ExifInterface.TAG_APERTURE_VALUE,
            ExifInterface.TAG_COPYRIGHT,
            ExifInterface.TAG_DATETIME,
            ExifInterface.TAG_EXPOSURE_TIME,
            ExifInterface.TAG_FOCAL_LENGTH,
            ExifInterface.TAG_F_NUMBER,
            ExifInterface.TAG_GPS_LATITUDE,
            ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE,
            ExifInterface.TAG_GPS_LONGITUDE_REF,
            ExifInterface.TAG_IMAGE_DESCRIPTION,
            ExifInterface.TAG_IMAGE_LENGTH,
            ExifInterface.TAG_IMAGE_WIDTH,
            ExifInterface.TAG_ISO_SPEED_RATINGS,
            ExifInterface.TAG_MAKE,
            ExifInterface.TAG_MODEL,
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.TAG_SHUTTER_SPEED_VALUE,
            ExifInterface.TAG_SOFTWARE
    };
    @DataType
    private static final int TYPE_DOUBLE = 1;
    @DataType
    private static final int TYPE_INT = 0;
    /** Maps an EXIF tag to a {@link DataType data type} */
    private static final Map<String, Integer> TYPE_MAPPING = new HashMap<>(137);
    @DataType
    private static final int TYPE_STRING = 2;

    static {
        TYPE_MAPPING.put(ExifInterface.TAG_APERTURE_VALUE, TYPE_DOUBLE);
        TYPE_MAPPING.put(ExifInterface.TAG_ARTIST, TYPE_STRING);
        TYPE_MAPPING.put(ExifInterface.TAG_BITS_PER_SAMPLE, TYPE_INT);
        TYPE_MAPPING.put(ExifInterface.TAG_BODY_SERIAL_NUMBER, TYPE_STRING);
        TYPE_MAPPING.put(ExifInterface.TAG_BRIGHTNESS_VALUE, TYPE_DOUBLE);
        TYPE_MAPPING.put(ExifInterface.TAG_CAMERA_OWNER_NAME, TYPE_STRING);
        TYPE_MAPPING.put(ExifInterface.TAG_CFA_PATTERN, TYPE_STRING);
        TYPE_MAPPING.put(ExifInterface.TAG_COLOR_SPACE, TYPE_INT);
        TYPE_MAPPING.put(ExifInterface.TAG_COMPONENTS_CONFIGURATION, TYPE_STRING);
        TYPE_MAPPING.put(ExifInterface.TAG_COMPRESSED_BITS_PER_PIXEL, TYPE_DOUBLE);
        TYPE_MAPPING.put(ExifInterface.TAG_COMPRESSION, TYPE_INT);
        TYPE_MAPPING.put(ExifInterface.TAG_CONTRAST, TYPE_INT);
        TYPE_MAPPING.put(ExifInterface.TAG_COPYRIGHT, TYPE_STRING);
        TYPE_MAPPING.put(ExifInterface.TAG_CUSTOM_RENDERED, TYPE_INT);
        TYPE_MAPPING.put(ExifInterface.TAG_DATETIME, TYPE_STRING);
        TYPE_MAPPING.put(ExifInterface.TAG_DATETIME_DIGITIZED, TYPE_STRING);
        TYPE_MAPPING.put(ExifInterface.TAG_DATETIME_ORIGINAL, TYPE_STRING);
        TYPE_MAPPING.put(ExifInterface.TAG_DEFAULT_CROP_SIZE, TYPE_INT);
        TYPE_MAPPING.put(ExifInterface.TAG_DEVICE_SETTING_DESCRIPTION, TYPE_STRING);
        TYPE_MAPPING.put(ExifInterface.TAG_DIGITAL_ZOOM_RATIO, TYPE_DOUBLE);
        TYPE_MAPPING.put(ExifInterface.TAG_DNG_VERSION, TYPE_INT);
        TYPE_MAPPING.put(ExifInterface.TAG_EXIF_VERSION, TYPE_STRING);
        TYPE_MAPPING.put(ExifInterface.TAG_EXPOSURE_BIAS_VALUE, TYPE_DOUBLE);
        TYPE_MAPPING.put(ExifInterface.TAG_EXPOSURE_INDEX, TYPE_DOUBLE);
        TYPE_MAPPING.put(ExifInterface.TAG_EXPOSURE_MODE, TYPE_INT);
        TYPE_MAPPING.put(ExifInterface.TAG_EXPOSURE_PROGRAM, TYPE_INT);
        TYPE_MAPPING.put(ExifInterface.TAG_EXPOSURE_TIME, TYPE_DOUBLE);
        TYPE_MAPPING.put(ExifInterface.TAG_FILE_SOURCE, TYPE_STRING);
        TYPE_MAPPING.put(ExifInterface.TAG_FLASH, TYPE_INT);
        TYPE_MAPPING.put(ExifInterface.TAG_FLASHPIX_VERSION, TYPE_STRING);
        TYPE_MAPPING.put(ExifInterface.TAG_FLASH_ENERGY, TYPE_DOUBLE);
        TYPE_MAPPING.put(ExifInterface.TAG_FOCAL_LENGTH, TYPE_DOUBLE);
        TYPE_MAPPING.put(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, TYPE_INT);
        TYPE_MAPPING.put(ExifInterface.TAG_FOCAL_PLANE_RESOLUTION_UNIT, TYPE_INT);
        TYPE_MAPPING.put(ExifInterface.TAG_FOCAL_PLANE_X_RESOLUTION, TYPE_DOUBLE);
        TYPE_MAPPING.put(ExifInterface.TAG_FOCAL_PLANE_Y_RESOLUTION, TYPE_DOUBLE);
        TYPE_MAPPING.put(ExifInterface.TAG_F_NUMBER, TYPE_DOUBLE);
        TYPE_MAPPING.put(ExifInterface.TAG_GAIN_CONTROL, TYPE_INT);
        TYPE_MAPPING.put(ExifInterface.TAG_GPS_ALTITUDE, TYPE_DOUBLE);
        TYPE_MAPPING.put(ExifInterface.TAG_GPS_ALTITUDE_REF, TYPE_INT);
        TYPE_MAPPING.put(ExifInterface.TAG_GPS_AREA_INFORMATION, TYPE_STRING);
        TYPE_MAPPING.put(ExifInterface.TAG_GPS_DATESTAMP, TYPE_STRING);
        TYPE_MAPPING.put(ExifInterface.TAG_GPS_DEST_BEARING, TYPE_DOUBLE);
        TYPE_MAPPING.put(ExifInterface.TAG_GPS_DEST_BEARING_REF, TYPE_STRING);
        TYPE_MAPPING.put(ExifInterface.TAG_GPS_DEST_DISTANCE, TYPE_DOUBLE);
        TYPE_MAPPING.put(ExifInterface.TAG_GPS_DEST_DISTANCE_REF, TYPE_STRING);
        TYPE_MAPPING.put(ExifInterface.TAG_GPS_DEST_LATITUDE, TYPE_DOUBLE);
        TYPE_MAPPING.put(ExifInterface.TAG_GPS_DEST_LATITUDE_REF, TYPE_STRING);
        TYPE_MAPPING.put(ExifInterface.TAG_GPS_DEST_LONGITUDE, TYPE_DOUBLE);
        TYPE_MAPPING.put(ExifInterface.TAG_GPS_DEST_LONGITUDE_REF, TYPE_STRING);
        TYPE_MAPPING.put(ExifInterface.TAG_GPS_DIFFERENTIAL, TYPE_INT);
        TYPE_MAPPING.put(ExifInterface.TAG_GPS_DOP, TYPE_DOUBLE);
        TYPE_MAPPING.put(ExifInterface.TAG_GPS_IMG_DIRECTION, TYPE_DOUBLE);
        TYPE_MAPPING.put(ExifInterface.TAG_GPS_IMG_DIRECTION_REF, TYPE_STRING);
        TYPE_MAPPING.put(ExifInterface.TAG_GPS_LATITUDE, TYPE_STRING);
        TYPE_MAPPING.put(ExifInterface.TAG_GPS_LATITUDE_REF, TYPE_STRING);
        TYPE_MAPPING.put(ExifInterface.TAG_GPS_LONGITUDE, TYPE_STRING);
        TYPE_MAPPING.put(ExifInterface.TAG_GPS_LONGITUDE_REF, TYPE_STRING);
        TYPE_MAPPING.put(ExifInterface.TAG_GPS_MAP_DATUM, TYPE_STRING);
        TYPE_MAPPING.put(ExifInterface.TAG_GPS_MEASURE_MODE, TYPE_STRING);
        TYPE_MAPPING.put(ExifInterface.TAG_GPS_PROCESSING_METHOD, TYPE_STRING);
        TYPE_MAPPING.put(ExifInterface.TAG_GPS_SATELLITES, TYPE_STRING);
        TYPE_MAPPING.put(ExifInterface.TAG_GPS_SPEED, TYPE_DOUBLE);
        TYPE_MAPPING.put(ExifInterface.TAG_GPS_SPEED_REF, TYPE_STRING);
        TYPE_MAPPING.put(ExifInterface.TAG_GPS_STATUS, TYPE_STRING);
        TYPE_MAPPING.put(ExifInterface.TAG_GPS_TIMESTAMP, TYPE_STRING);
        TYPE_MAPPING.put(ExifInterface.TAG_GPS_TRACK, TYPE_DOUBLE);
        TYPE_MAPPING.put(ExifInterface.TAG_GPS_TRACK_REF, TYPE_STRING);
        TYPE_MAPPING.put(ExifInterface.TAG_GPS_VERSION_ID, TYPE_STRING);
        TYPE_MAPPING.put(ExifInterface.TAG_IMAGE_DESCRIPTION, TYPE_STRING);
        TYPE_MAPPING.put(ExifInterface.TAG_IMAGE_LENGTH, TYPE_INT);
        TYPE_MAPPING.put(ExifInterface.TAG_IMAGE_UNIQUE_ID, TYPE_STRING);
        TYPE_MAPPING.put(ExifInterface.TAG_IMAGE_WIDTH, TYPE_INT);
        TYPE_MAPPING.put(ExifInterface.TAG_INTEROPERABILITY_INDEX, TYPE_STRING);
        TYPE_MAPPING.put(ExifInterface.TAG_ISO_SPEED_RATINGS, TYPE_INT);
        TYPE_MAPPING.put(ExifInterface.TAG_JPEG_INTERCHANGE_FORMAT, TYPE_INT);
        TYPE_MAPPING.put(ExifInterface.TAG_JPEG_INTERCHANGE_FORMAT_LENGTH, TYPE_INT);
        TYPE_MAPPING.put(ExifInterface.TAG_LENS_MAKE, TYPE_STRING);
        TYPE_MAPPING.put(ExifInterface.TAG_LENS_MODEL, TYPE_STRING);
        TYPE_MAPPING.put(ExifInterface.TAG_LENS_SERIAL_NUMBER, TYPE_STRING);
        TYPE_MAPPING.put(ExifInterface.TAG_LIGHT_SOURCE, TYPE_INT);
        TYPE_MAPPING.put(ExifInterface.TAG_MAKE, TYPE_STRING);
        TYPE_MAPPING.put(ExifInterface.TAG_MAKER_NOTE, TYPE_STRING);
        TYPE_MAPPING.put(ExifInterface.TAG_MAX_APERTURE_VALUE, TYPE_DOUBLE);
        TYPE_MAPPING.put(ExifInterface.TAG_METERING_MODE, TYPE_INT);
        TYPE_MAPPING.put(ExifInterface.TAG_MODEL, TYPE_STRING);
        TYPE_MAPPING.put(ExifInterface.TAG_NEW_SUBFILE_TYPE, TYPE_INT);
        TYPE_MAPPING.put(ExifInterface.TAG_OECF, TYPE_STRING);
        TYPE_MAPPING.put(ExifInterface.TAG_ORF_ASPECT_FRAME, TYPE_INT);
        TYPE_MAPPING.put(ExifInterface.TAG_ORF_PREVIEW_IMAGE_LENGTH, TYPE_INT);
        TYPE_MAPPING.put(ExifInterface.TAG_ORF_PREVIEW_IMAGE_START, TYPE_INT);
        TYPE_MAPPING.put(ExifInterface.TAG_ORIENTATION, TYPE_INT);
        TYPE_MAPPING.put(ExifInterface.TAG_PHOTOMETRIC_INTERPRETATION, TYPE_INT);
        TYPE_MAPPING.put(ExifInterface.TAG_PIXEL_X_DIMENSION, TYPE_INT);
        TYPE_MAPPING.put(ExifInterface.TAG_PIXEL_Y_DIMENSION, TYPE_INT);
        TYPE_MAPPING.put(ExifInterface.TAG_PLANAR_CONFIGURATION, TYPE_INT);
        TYPE_MAPPING.put(ExifInterface.TAG_PRIMARY_CHROMATICITIES, TYPE_DOUBLE);
        TYPE_MAPPING.put(ExifInterface.TAG_REFERENCE_BLACK_WHITE, TYPE_DOUBLE);
        TYPE_MAPPING.put(ExifInterface.TAG_RELATED_SOUND_FILE, TYPE_STRING);
        TYPE_MAPPING.put(ExifInterface.TAG_RESOLUTION_UNIT, TYPE_INT);
        TYPE_MAPPING.put(ExifInterface.TAG_ROWS_PER_STRIP, TYPE_INT);
        TYPE_MAPPING.put(ExifInterface.TAG_RW2_ISO, TYPE_INT);
        TYPE_MAPPING.put(ExifInterface.TAG_RW2_SENSOR_BOTTOM_BORDER, TYPE_INT);
        TYPE_MAPPING.put(ExifInterface.TAG_RW2_SENSOR_LEFT_BORDER, TYPE_INT);
        TYPE_MAPPING.put(ExifInterface.TAG_RW2_SENSOR_RIGHT_BORDER, TYPE_INT);
        TYPE_MAPPING.put(ExifInterface.TAG_RW2_SENSOR_TOP_BORDER, TYPE_INT);
        TYPE_MAPPING.put(ExifInterface.TAG_SAMPLES_PER_PIXEL, TYPE_INT);
        TYPE_MAPPING.put(ExifInterface.TAG_SATURATION, TYPE_INT);
        TYPE_MAPPING.put(ExifInterface.TAG_SCENE_CAPTURE_TYPE, TYPE_INT);
        TYPE_MAPPING.put(ExifInterface.TAG_SCENE_TYPE, TYPE_STRING);
        TYPE_MAPPING.put(ExifInterface.TAG_SENSING_METHOD, TYPE_INT);
        TYPE_MAPPING.put(ExifInterface.TAG_SHARPNESS, TYPE_INT);
        TYPE_MAPPING.put(ExifInterface.TAG_SHUTTER_SPEED_VALUE, TYPE_DOUBLE);
        TYPE_MAPPING.put(ExifInterface.TAG_SOFTWARE, TYPE_STRING);
        TYPE_MAPPING.put(ExifInterface.TAG_SPATIAL_FREQUENCY_RESPONSE, TYPE_STRING);
        TYPE_MAPPING.put(ExifInterface.TAG_SPECTRAL_SENSITIVITY, TYPE_STRING);
        TYPE_MAPPING.put(ExifInterface.TAG_STRIP_BYTE_COUNTS, TYPE_INT);
        TYPE_MAPPING.put(ExifInterface.TAG_STRIP_OFFSETS, TYPE_INT);
        TYPE_MAPPING.put(ExifInterface.TAG_SUBFILE_TYPE, TYPE_INT);
        TYPE_MAPPING.put(ExifInterface.TAG_SUBJECT_AREA, TYPE_INT);
        TYPE_MAPPING.put(ExifInterface.TAG_SUBJECT_DISTANCE, TYPE_DOUBLE);
        TYPE_MAPPING.put(ExifInterface.TAG_SUBJECT_DISTANCE_RANGE, TYPE_INT);
        TYPE_MAPPING.put(ExifInterface.TAG_SUBJECT_LOCATION, TYPE_INT);
        TYPE_MAPPING.put(ExifInterface.TAG_SUBSEC_TIME, TYPE_STRING);
        TYPE_MAPPING.put(ExifInterface.TAG_SUBSEC_TIME_DIGITIZED, TYPE_STRING);
        TYPE_MAPPING.put(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, TYPE_STRING);
        TYPE_MAPPING.put(ExifInterface.TAG_THUMBNAIL_IMAGE_LENGTH, TYPE_INT);
        TYPE_MAPPING.put(ExifInterface.TAG_THUMBNAIL_IMAGE_WIDTH, TYPE_INT);
        TYPE_MAPPING.put(ExifInterface.TAG_TRANSFER_FUNCTION, TYPE_INT);
        TYPE_MAPPING.put(ExifInterface.TAG_USER_COMMENT, TYPE_STRING);
        TYPE_MAPPING.put(ExifInterface.TAG_WHITE_BALANCE, TYPE_INT);
        TYPE_MAPPING.put(ExifInterface.TAG_WHITE_POINT, TYPE_DOUBLE);
        TYPE_MAPPING.put(ExifInterface.TAG_X_RESOLUTION, TYPE_DOUBLE);
        TYPE_MAPPING.put(ExifInterface.TAG_Y_CB_CR_COEFFICIENTS, TYPE_DOUBLE);
        TYPE_MAPPING.put(ExifInterface.TAG_Y_CB_CR_POSITIONING, TYPE_INT);
        TYPE_MAPPING.put(ExifInterface.TAG_Y_CB_CR_SUB_SAMPLING, TYPE_INT);
        TYPE_MAPPING.put(ExifInterface.TAG_Y_RESOLUTION, TYPE_DOUBLE);
    }

    /**
     * Retrieves EXIF data from a stream.
     *
     * @param stream InputStream referring to a file
     * @param tags   EXIF tags to return; can be {@code null} to return {@link #DEFAULT_EXIF_TAGS}
     * @return the bundle of metadata
     * @throws IOException          if an I/O error occurs
     * @throws NullPointerException if {@code stream} is {@code null}
     */
    @NonNull
    public static Bundle getMetadata(@NonNull InputStream stream, @Nullable String[] tags) throws IOException {
        final Bundle metadata = new Bundle();
        if (tags == null) tags = DEFAULT_EXIF_TAGS;
        final ExifInterface exifInterface = new ExifInterface(stream);
        final Bundle exifData = new Bundle(tags.length);
        for (String tag : tags) {
            @DataType Integer t = TYPE_MAPPING.get(tag);
            if (t == null) continue;
            if (t == TYPE_INT) {
                int data = exifInterface.getAttributeInt(tag, Integer.MIN_VALUE);
                if (data != Integer.MIN_VALUE) exifData.putInt(tag, data);
            } else if (t == TYPE_DOUBLE) {
                double data = exifInterface.getAttributeDouble(tag, Double.MIN_VALUE);
                if (data != Double.MIN_VALUE) exifData.putDouble(tag, data);
            } else if (t == TYPE_STRING) {
                String data = exifInterface.getAttribute(tag);
                if (data != null) exifData.putString(tag, data);
            }
        }
        if (BuildConfig.DEBUG) {
            Set<String> keys = exifData.keySet();
            Log.i(MetadataReader.class.getSimpleName(), "Exif data contains " + exifData.size() + " key(s)");
            for (String key : keys) {
                Object value = exifData.get(key);
                Log.i(MetadataReader.class.getSimpleName(), key + "=" + value + " (" + (value != null ? value.getClass().getSimpleName() : "<null>") + ")");
            }
        }
        final List<String> metadataTypes = new ArrayList<>(1);
        if (exifData.size() > 0) {
            metadata.putBundle(DocumentsContract.METADATA_EXIF, exifData);
            metadataTypes.add(DocumentsContract.METADATA_EXIF);
        }
        metadata.putStringArray(DocumentsContract.METADATA_TYPES, metadataTypes.toArray(new String[0]));
        return metadata;
    }

    /**
     * Searches exif data for a given query.
     *
     * @param queryLower search query in lower case
     * @param exifData   exif data to search
     * @return {@code true} if any of the searched tags matches the query
     */
    public static boolean match(@NonNull final String queryLower, final Bundle exifData) {
        if (exifData == null) return false;
        for (String key : FOR_SEARCHES) {
            String value = exifData.getString(key);
            if (value != null && value.trim().toLowerCase().contains(queryLower)) return true;
        }
        return false;
    }

    /**
     * @param dateTime {@link ExifInterface#TAG_DATETIME} or {@link ExifInterface#TAG_DATETIME_ORIGINAL} value
     * @return Date
     */
    @Nullable
    public static Date parseDateTime(String dateTime) {
        if (dateTime == null) return null;
        Date date = null;
        try {
            date = DATE_FORMAT.parse(dateTime);
        } catch (Exception ignored) {
        }
        return date;
    }

    /**
     * Parses a GPSLatitude or GPSLongitude value.<br>
     * Expects something like "13/1,22/1,225407/10000"; returns something like "13°22'22.5407""
     * @param value {@link ExifInterface#TAG_GPS_LATITUDE} or {@link ExifInterface#TAG_GPS_LONGITUDE} value
     * @return GPS value
     */
    @Nullable
    public static String parseGps(String value) {
        if (value == null) return null;
        final StringBuilder sb = new StringBuilder(16);
        final String[] parts = value.split(",");
        if (parts.length != 3) return null;
        int slash;
        String[] units = new String[] {"°", "'", "\""};
        for (int i = 0; i < 3; i++) {
            slash = parts[i].indexOf('/');
            if (slash < 0) {
                sb.append(parts[i]);
            } else {
                int val = Util.parseInt(parts[i].substring(0, slash), 0);
                int div = Util.parseInt(parts[i].substring(slash + 1), 1);
                if (div < 1) return null;
                else if (div == 1) sb.append(val);
                else sb.append(Math.round(val / (float)div));
            }
            sb.append(units[i]);
        }
        return sb.toString();
    }

    private MetadataReader() {}

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({TYPE_INT, TYPE_DOUBLE, TYPE_STRING})
    @interface DataType {}
}
