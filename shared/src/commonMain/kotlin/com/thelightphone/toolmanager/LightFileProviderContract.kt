package com.thelightphone.toolmanager

// Cursor column names used by LightFileProvider-compatible ContentProviders (see the
// client module's LightFileProvider) and read by ContentResolverFileTree on the server
// side. Shared here so the two sides of that contract can't drift apart.
const val COLUMN_IS_DIRECTORY = "is_directory"
const val COLUMN_LAST_MODIFIED = "last_modified"
