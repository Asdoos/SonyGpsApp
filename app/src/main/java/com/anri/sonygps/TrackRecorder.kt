package com.anri.sonygps

import android.content.Context
import android.location.Location
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.*

/**
 * Records the GPS fixes of one session as a GPX 1.1 track.
 *
 * Purpose: photos taken while the BLE link was down carry no location. The GPX
 * file can be used afterwards to geotag them (Lightroom, darktable, exiftool, …).
 *
 * The closing tags are rewritten after every point, so the file is a valid GPX
 * document at any time — even if the process is killed mid-session.
 */
class TrackRecorder private constructor(val file: File) : Closeable {

    private val raf = RandomAccessFile(file, "rw")
    private val isoUtc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    private var lastTime = 0L

    var pointCount = 0
        private set

    init {
        raf.setLength(0)
        raf.write(header(file.nameWithoutExtension).toByteArray())
        raf.write(FOOTER)
    }

    fun add(location: Location) {
        if (location.accuracy > MAX_ACCURACY_M || location.time == lastTime) return
        lastTime = location.time

        // Locale.US: GPX requires '.' as decimal separator
        val point = buildString {
            append("   <trkpt lat=\"%.7f\" lon=\"%.7f\">".format(Locale.US, location.latitude, location.longitude))
            if (location.hasAltitude()) append("<ele>%.1f</ele>".format(Locale.US, location.altitude))
            append("<time>").append(isoUtc.format(Date(location.time))).append("</time>")
            append("</trkpt>\n")
        }
        raf.seek(raf.length() - FOOTER.size)
        raf.write(point.toByteArray())
        raf.write(FOOTER)
        pointCount++
    }

    /** Closes the file; a track without any point is deleted. */
    override fun close() {
        raf.close()
        if (pointCount == 0) file.delete()
    }

    companion object {
        /** Fixes worse than this are not useful for geotagging and are skipped. */
        private const val MAX_ACCURACY_M = 100f

        private val FOOTER = "  </trkseg>\n </trk>\n</gpx>\n".toByteArray()

        private fun header(name: String) =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<gpx version=\"1.1\" creator=\"Sony GPS Link\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n" +
            " <trk>\n" +
            "  <name>$name</name>\n" +
            "  <trkseg>\n"

        /** Directory shared via FileProvider (see res/xml/file_paths.xml). */
        fun tracksDir(context: Context) = File(context.filesDir, "tracks")

        fun start(context: Context): TrackRecorder {
            val dir = tracksDir(context).apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
            return TrackRecorder(File(dir, "track_$stamp.gpx"))
        }

        /** All recorded tracks, newest first. */
        fun listTracks(context: Context): List<File> =
            tracksDir(context).listFiles { f -> f.extension == "gpx" }
                ?.sortedByDescending { it.name }
                ?: emptyList()
    }
}
