package com.al.pluginsecure

import androidx.core.content.FileProvider

/**
 * Dedicated FileProvider for the plugin's verified-photo files.
 *
 * The merged AndroidManifest cannot contain two <provider> elements with the same
 * `android:name`. The Capacitor 2 host app template already declares
 * androidx.core.content.FileProvider (authority ${applicationId}.fileprovider), so the
 * plugin declaring that same class name fails manifest merging with:
 *
 *   Attribute provider#androidx.core.content.FileProvider@authorities
 *   ... is also present at [:al-pluginsecure] AndroidManifest.xml
 *
 * Subclassing gives the plugin its own `android:name` while keeping its own authority
 * (${applicationId}.pluginsecure.fileprovider), which is what
 * CameraVerifiedActivity passes to FileProvider.getUriForFile().
 */
class PluginSecureFileProvider : FileProvider()
