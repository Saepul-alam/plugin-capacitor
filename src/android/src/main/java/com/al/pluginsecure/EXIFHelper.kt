package com.al.pluginsecure

import android.location.Location
import android.media.ExifInterface
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object EXIFHelper {
    fun writeVerifiedEXIF(
        photoFile: File,
        location: Location,
        gpsAgeMs: Long,
        provider: String
    ) {
        try {
            val exif = ExifInterface(photoFile.absolutePath)

            val lat = location.latitude
            exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE, toDMS(Math.abs(lat)))
            exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, if (lat >= 0) "N" else "S")

            val lon = location.longitude
            exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, toDMS(Math.abs(lon)))
            exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, if (lon >= 0) "E" else "W")

            exif.setAttribute(ExifInterface.TAG_GPS_ALTITUDE, location.altitude.toString())
            exif.setAttribute(ExifInterface.TAG_GPS_ALTITUDE_REF, if (location.altitude >= 0) "0" else "1")

            exif.setAttribute(ExifInterface.TAG_GPS_TIMESTAMP, getGPSTimeStamp(location.time))
            exif.setAttribute(ExifInterface.TAG_GPS_DATESTAMP, getGPSDateStamp(location.time))

            val verificationTag = String.format(
                Locale.US,
                "VERIFIED|ACCURACY:%.1f|AGE_MS:%d|PROVIDER:%s",
                location.accuracy, gpsAgeMs, provider
            )
            exif.setAttribute(ExifInterface.TAG_USER_COMMENT, verificationTag)

            exif.setAttribute(ExifInterface.TAG_SOFTWARE, "PluginSecure/1.0")

            exif.saveAttributes()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    private fun toDMS(coordinate: Double): String {
        val degrees = coordinate.toInt()
        val minutesFloat = (coordinate - degrees) * 60
        val minutes = minutesFloat.toInt()
        val seconds = (minutesFloat - minutes) * 60
        return String.format(Locale.US, "%d/1,%d/1,%d/1000", degrees, minutes, (seconds * 1000).toInt())
    }

    private fun getGPSTimeStamp(timeMs: Long): String {
        return SimpleDateFormat("HH/mm/ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(timeMs))
    }

    private fun getGPSDateStamp(timeMs: Long): String {
        return SimpleDateFormat("yyyy:MM:dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(timeMs))
    }
}