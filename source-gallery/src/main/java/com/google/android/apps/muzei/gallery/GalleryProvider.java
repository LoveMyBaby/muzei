/*
 * Copyright 2014 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.apps.muzei.gallery;

import android.content.ContentProvider;
import android.content.ContentProviderOperation;
import android.content.ContentProviderResult;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.OperationApplicationException;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteQueryBuilder;
import android.net.Uri;
import android.provider.BaseColumns;
import android.support.annotation.NonNull;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;

/**
 * Provides access to the Gallery's chosen photos and metadata
 */
public class GalleryProvider extends ContentProvider {
    /**
     * The incoming URI matches the CHOSEN PHOTOS URI pattern
     */
    private static final int CHOSEN_PHOTOS = 1;
    /**
     * The incoming URI matches the METADATA CACHE URI pattern
     */
    private static final int METADATA_CACHE = 2;
    /**
     * The database that the provider uses as its underlying data store
     */
    private static final String DATABASE_NAME = "gallery_source.db";
    /**
     * The database version
     */
    private static final int DATABASE_VERSION = 2;
    /**
     * A UriMatcher instance
     */
    private static final UriMatcher uriMatcher = GalleryProvider.buildUriMatcher();
    /**
     * An identity all column projection mapping for Chosen Photos
     */
    private final HashMap<String, String> allChosenPhotosColumnProjectionMap =
            GalleryProvider.buildAllChosenPhotosColumnProjectionMap();
    /**
     * An identity all column projection mapping for Metadata Cache
     */
    private final HashMap<String, String> allMetadataCacheColumnProjectionMap =
            GalleryProvider.buildAllMetadataCacheColumnProjectionMap();
    /**
     * Handle to a new DatabaseHelper.
     */
    private DatabaseHelper databaseHelper;
    /**
     * Whether we should hold notifyChange() calls due to an ongoing applyBatch operation
     */
    private boolean holdNotifyChange = false;
    /**
     * Set of Uris that should be applied when the ongoing applyBatch operation finishes
     */
    private LinkedHashSet<Uri> pendingNotifyChange = new LinkedHashSet<>();

    /**
     * Creates and initializes a column project for all columns for Chosen Photos
     *
     * @return The all column projection map for Chosen Photos
     */
    private static HashMap<String, String> buildAllChosenPhotosColumnProjectionMap() {
        final HashMap<String, String> allColumnProjectionMap = new HashMap<>();
        allColumnProjectionMap.put(BaseColumns._ID, BaseColumns._ID);
        allColumnProjectionMap.put(GalleryContract.ChosenPhotos.COLUMN_NAME_URI,
                GalleryContract.ChosenPhotos.COLUMN_NAME_URI);
        return allColumnProjectionMap;
    }

    /**
     * Creates and initializes a column project for all columns for Sources
     *
     * @return The all column projection map for Sources
     */
    private static HashMap<String, String> buildAllMetadataCacheColumnProjectionMap() {
        final HashMap<String, String> allColumnProjectionMap = new HashMap<>();
        allColumnProjectionMap.put(BaseColumns._ID, BaseColumns._ID);
        allColumnProjectionMap.put(GalleryContract.MetadataCache.COLUMN_NAME_URI,
                GalleryContract.MetadataCache.COLUMN_NAME_URI);
        allColumnProjectionMap.put(GalleryContract.MetadataCache.COLUMN_NAME_DATETIME,
                GalleryContract.MetadataCache.COLUMN_NAME_DATETIME);
        allColumnProjectionMap.put(GalleryContract.MetadataCache.COLUMN_NAME_LOCATION,
                GalleryContract.MetadataCache.COLUMN_NAME_LOCATION);
        return allColumnProjectionMap;
    }

    /**
     * Creates and initializes the URI matcher
     *
     * @return the URI Matcher
     */
    private static UriMatcher buildUriMatcher() {
        final UriMatcher matcher = new UriMatcher(UriMatcher.NO_MATCH);
        matcher.addURI(GalleryContract.AUTHORITY, GalleryContract.ChosenPhotos.TABLE_NAME,
                GalleryProvider.CHOSEN_PHOTOS);
        matcher.addURI(GalleryContract.AUTHORITY, GalleryContract.MetadataCache.TABLE_NAME,
                GalleryProvider.METADATA_CACHE);
        return matcher;
    }

    @NonNull
    @Override
    public ContentProviderResult[] applyBatch(@NonNull final ArrayList<ContentProviderOperation> operations)
            throws OperationApplicationException {
        holdNotifyChange = true;
        try {
            return super.applyBatch(operations);
        } finally {
            holdNotifyChange = false;
            Context context = getContext();
            if (context != null) {
                ContentResolver contentResolver = context.getContentResolver();
                Iterator<Uri> iterator = pendingNotifyChange.iterator();
                while (iterator.hasNext()) {
                    Uri uri = iterator.next();
                    contentResolver.notifyChange(uri, null);
                    iterator.remove();
                }
            }
        }
    }

    private void notifyChange(Uri uri) {
        if (holdNotifyChange) {
            pendingNotifyChange.add(uri);
        } else {
            Context context = getContext();
            if (context == null) {
                return;
            }
            context.getContentResolver().notifyChange(uri, null);
        }
    }

    @Override
    public int delete(@NonNull final Uri uri, final String selection, final String[] selectionArgs) {
        if (GalleryProvider.uriMatcher.match(uri) == GalleryProvider.CHOSEN_PHOTOS) {
            return deleteChosenPhotos(uri, selection, selectionArgs);
        } else if (GalleryProvider.uriMatcher.match(uri) == GalleryProvider.METADATA_CACHE) {
            throw new UnsupportedOperationException("Deletes are not supported");
        } else {
            throw new IllegalArgumentException("Unknown URI " + uri);
        }
    }

    private int deleteChosenPhotos(@NonNull final Uri uri, final String selection, final String[] selectionArgs) {
        // Opens the database object in "write" mode.
        final SQLiteDatabase db = databaseHelper.getWritableDatabase();
        int count;
        // Does the delete based on the incoming URI pattern.
        switch (GalleryProvider.uriMatcher.match(uri)) {
            case CHOSEN_PHOTOS:
                // If the incoming pattern matches the general pattern for
                // sources, does a delete based on the incoming "where"
                // column and arguments.
                count = db.delete(GalleryContract.ChosenPhotos.TABLE_NAME, selection, selectionArgs);
                break;
            default:
                throw new IllegalArgumentException("Unknown URI " + uri);
        }
        if (count > 0) {
            notifyChange(uri);
        }
        return count;
    }

    @Override
    public String getType(@NonNull final Uri uri) {
        /**
         * Chooses the MIME type based on the incoming URI pattern
         */
        switch (GalleryProvider.uriMatcher.match(uri)) {
            case CHOSEN_PHOTOS:
                // If the pattern is for chosen photos, returns the chosen photos content type.
                return GalleryContract.ChosenPhotos.CONTENT_TYPE;
            case METADATA_CACHE:
                // If the pattern is for metadata cache, returns the metadata cache content type.
                return GalleryContract.MetadataCache.CONTENT_TYPE;
            default:
                throw new IllegalArgumentException("Unknown URI " + uri);
        }
    }

    @Override
    public Uri insert(@NonNull final Uri uri, final ContentValues values) {
        if (GalleryProvider.uriMatcher.match(uri) == GalleryProvider.CHOSEN_PHOTOS) {
            return insertChosenPhotos(uri, values);
        } else if (GalleryProvider.uriMatcher.match(uri) == GalleryProvider.METADATA_CACHE) {
            return insertMetadataCache(uri, values);
        } else {
            throw new IllegalArgumentException("Unknown URI " + uri);
        }
    }

    private Uri insertChosenPhotos(@NonNull final Uri uri, final ContentValues values) {
        if (values == null) {
            throw new IllegalArgumentException("Invalid ContentValues: must not be null");
        }
        if (!values.containsKey(GalleryContract.ChosenPhotos.COLUMN_NAME_URI))
            throw new IllegalArgumentException("Initial values must contain URI " + values);
        final SQLiteDatabase db = databaseHelper.getWritableDatabase();
        long rowId = db.insert(GalleryContract.ChosenPhotos.TABLE_NAME,
                GalleryContract.ChosenPhotos.COLUMN_NAME_URI, values);
        // If the insert succeeded, the row ID exists.
        if (rowId > 0)
        {
            // Creates a URI with the chosen photos ID pattern and the new row ID appended to it.
            final Uri chosenPhotoUri = ContentUris.withAppendedId(GalleryContract.ChosenPhotos.CONTENT_URI, rowId);
            notifyChange(chosenPhotoUri);
            return chosenPhotoUri;
        }
        // If the insert didn't succeed, then the rowID is <= 0
        throw new SQLException("Failed to insert row into " + uri);
    }

    private Uri insertMetadataCache(@NonNull final Uri uri, final ContentValues initialValues) {
        if (!initialValues.containsKey(GalleryContract.MetadataCache.COLUMN_NAME_URI))
            throw new IllegalArgumentException("Initial values must contain URI " + initialValues);
        final SQLiteDatabase db = databaseHelper.getWritableDatabase();
        final long rowId = db.insert(GalleryContract.MetadataCache.TABLE_NAME,
                GalleryContract.MetadataCache.COLUMN_NAME_URI, initialValues);
        // If the insert succeeded, the row ID exists.
        if (rowId > 0)
        {
            // Creates a URI with the metadata cache ID pattern and the new row ID appended to it.
            final Uri metadataCacheUri = ContentUris.withAppendedId(GalleryContract.MetadataCache.CONTENT_URI, rowId);
            notifyChange(metadataCacheUri);
            return metadataCacheUri;
        }
        // If the insert didn't succeed, then the rowID is <= 0
        throw new SQLException("Failed to insert row into " + uri);
    }

    /**
     * Creates the underlying DatabaseHelper
     *
     * @see android.content.ContentProvider#onCreate()
     */
    @Override
    public boolean onCreate() {
        databaseHelper = new DatabaseHelper(getContext());
        return true;
    }

    @Override
    public Cursor query(@NonNull final Uri uri, final String[] projection, final String selection,
                        final String[] selectionArgs, final String sortOrder) {
        if (GalleryProvider.uriMatcher.match(uri) == GalleryProvider.CHOSEN_PHOTOS) {
            return queryChosenPhotos(uri, projection, selection, selectionArgs, sortOrder);
        } else if (GalleryProvider.uriMatcher.match(uri) == GalleryProvider.METADATA_CACHE) {
            return queryMetadataCache(uri, projection, selection, selectionArgs, sortOrder);
        } else {
            throw new IllegalArgumentException("Unknown URI " + uri);
        }
    }

    private Cursor queryChosenPhotos(@NonNull final Uri uri, final String[] projection, final String selection,
                               final String[] selectionArgs, final String sortOrder) {
        ContentResolver contentResolver = getContext() != null ? getContext().getContentResolver() : null;
        if (contentResolver == null) {
            return null;
        }
        final SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
        qb.setTables(GalleryContract.ChosenPhotos.TABLE_NAME);
        qb.setProjectionMap(allChosenPhotosColumnProjectionMap);
        final SQLiteDatabase db = databaseHelper.getReadableDatabase();
        String orderBy;
        if (TextUtils.isEmpty(sortOrder))
            orderBy = GalleryContract.ChosenPhotos.DEFAULT_SORT_ORDER;
        else
            orderBy = sortOrder;
        final Cursor c = qb.query(db, projection, selection, selectionArgs, null, null, orderBy, null);
        c.setNotificationUri(contentResolver, uri);
        return c;
    }

    private Cursor queryMetadataCache(@NonNull final Uri uri, final String[] projection, final String selection,
                               final String[] selectionArgs, final String sortOrder) {
        ContentResolver contentResolver = getContext() != null ? getContext().getContentResolver() : null;
        if (contentResolver == null) {
            return null;
        }
        final SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
        qb.setTables(GalleryContract.MetadataCache.TABLE_NAME);
        qb.setProjectionMap(allMetadataCacheColumnProjectionMap);
        final SQLiteDatabase db = databaseHelper.getReadableDatabase();
        String orderBy;
        if (TextUtils.isEmpty(sortOrder))
            orderBy = GalleryContract.MetadataCache.DEFAULT_SORT_ORDER;
        else
            orderBy = sortOrder;
        final Cursor c = qb.query(db, projection, selection, selectionArgs, null, null, orderBy, null);
        c.setNotificationUri(contentResolver, uri);
        return c;
    }

    @Override
    public int update(@NonNull final Uri uri, final ContentValues values, final String selection, final String[] selectionArgs) {
        if (GalleryProvider.uriMatcher.match(uri) == GalleryProvider.CHOSEN_PHOTOS) {
            throw new UnsupportedOperationException("Updates are not allowed");
        } else if (GalleryProvider.uriMatcher.match(uri) == GalleryProvider.METADATA_CACHE) {
            throw new UnsupportedOperationException("Updates are not allowed");
        } else {
            throw new IllegalArgumentException("Unknown URI " + uri);
        }
    }

    /**
     * This class helps open, create, and upgrade the database file.
     */
    static class DatabaseHelper extends SQLiteOpenHelper {

        DatabaseHelper(Context context) {
            super(context, DATABASE_NAME, null, DATABASE_VERSION);
        }

        /**
         * Creates the underlying database with table name and column names taken from the GalleryContract class.
         */
        @Override
        public void onCreate(SQLiteDatabase db) {
            onCreateChosenPhotos(db);
            onCreateMetadataCache(db);
        }

        private void onCreateChosenPhotos(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE " + GalleryContract.ChosenPhotos.TABLE_NAME + " ("
                    + BaseColumns._ID + " INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + GalleryContract.ChosenPhotos.COLUMN_NAME_URI + " TEXT NOT NULL,"
                    + "UNIQUE (" + GalleryContract.ChosenPhotos.COLUMN_NAME_URI + ") ON CONFLICT REPLACE)");
        }

        private void onCreateMetadataCache(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE " + GalleryContract.MetadataCache.TABLE_NAME + " ("
                    + BaseColumns._ID + " INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + GalleryContract.MetadataCache.COLUMN_NAME_URI + " TEXT NOT NULL,"
                    + GalleryContract.MetadataCache.COLUMN_NAME_DATETIME + " INTEGER,"
                    + GalleryContract.MetadataCache.COLUMN_NAME_LOCATION + " TEXT,"
                    + "UNIQUE (" + GalleryContract.MetadataCache.COLUMN_NAME_URI + ") ON CONFLICT REPLACE)");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            if (oldVersion < 2) {
                db.execSQL("DROP TABLE IF EXISTS " + GalleryContract.MetadataCache.TABLE_NAME);
                onCreateMetadataCache(db);
            }
        }
    }

}