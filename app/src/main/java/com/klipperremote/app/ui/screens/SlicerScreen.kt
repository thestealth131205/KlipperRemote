package com.klipperremote.app.ui.screens

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Forest
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Transform
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.klipperremote.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// =============================================================================
// SlicerScreen – STL laden, ausrichten, Profile verwalten, slicen (Vorbereitung)
// =============================================================================
// Eigenständiger Screen: leichtgewichtiger STL-Parser + Canvas-3D-Vorschau,
// schmale Topbar mit den Werkzeugen Stützen / Auto-Ausrichtung / Scale+Rotate /
// Profil-Menü. Profile (printer/process/filament) werden lokal importiert und
// gehen später an den slice-service (FastAPI vor dem Orca-CLI).

// ── kleine Vektor-/Matrix-Helfer ────────────────────────────────────────────

private data class Vec3(val x: Float, val y: Float, val z: Float) {
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun times(s: Float) = Vec3(x * s, y * s, z * s)
    fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun length() = sqrt(x * x + y * y + z * z)
    fun normalized(): Vec3 { val l = length(); return if (l < 1e-6f) this else Vec3(x / l, y / l, z / l) }
}

/** Row-major 3x3-Matrix als FloatArray(9). */
private fun matIdentity() = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

private fun matMul(a: FloatArray, b: FloatArray): FloatArray {
    val r = FloatArray(9)
    for (i in 0..2) for (j in 0..2) {
        var s = 0f
        for (k in 0..2) s += a[i * 3 + k] * b[k * 3 + j]
        r[i * 3 + j] = s
    }
    return r
}

private fun matVec(m: FloatArray, v: Vec3) = Vec3(
    m[0] * v.x + m[1] * v.y + m[2] * v.z,
    m[3] * v.x + m[4] * v.y + m[5] * v.z,
    m[6] * v.x + m[7] * v.y + m[8] * v.z
)

private fun rotAxis(axis: Vec3, angleRad: Float): FloatArray {
    val a = axis.normalized()
    val c = cos(angleRad); val s = sin(angleRad); val t = 1f - c
    return floatArrayOf(
        t * a.x * a.x + c,        t * a.x * a.y - s * a.z,  t * a.x * a.z + s * a.y,
        t * a.x * a.y + s * a.z,  t * a.y * a.y + c,        t * a.y * a.z - s * a.x,
        t * a.x * a.z - s * a.y,  t * a.y * a.z + s * a.x,  t * a.z * a.z + c
    )
}

// ── STL-Modell ──────────────────────────────────────────────────────────────

private class Tri(val a: Vec3, val b: Vec3, val c: Vec3, val n: Vec3) {
    fun area(): Float = ((b - a).cross(c - a)).length() * 0.5f
}

private class StlModel(val tris: List<Tri>) {
    val min: Vec3
    val max: Vec3
    val center: Vec3
    init {
        var mnx = Float.MAX_VALUE; var mny = Float.MAX_VALUE; var mnz = Float.MAX_VALUE
        var mxx = -Float.MAX_VALUE; var mxy = -Float.MAX_VALUE; var mxz = -Float.MAX_VALUE
        for (t in tris) for (v in listOf(t.a, t.b, t.c)) {
            mnx = min(mnx, v.x); mny = min(mny, v.y); mnz = min(mnz, v.z)
            mxx = max(mxx, v.x); mxy = max(mxy, v.y); mxz = max(mxz, v.z)
        }
        if (tris.isEmpty()) { mnx = 0f; mny = 0f; mnz = 0f; mxx = 0f; mxy = 0f; mxz = 0f }
        min = Vec3(mnx, mny, mnz); max = Vec3(mxx, mxy, mxz)
        center = (min + max) * 0.5f
    }
}

/** Parst binäres oder ASCII-STL. Gibt null bei Fehler/leer zurück. */
private fun parseStl(bytes: ByteArray): StlModel? {
    if (bytes.size < 84) return parseAsciiStl(bytes)
    // Binär-Heuristik: erwartete Größe == 84 + count*50
    val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    bb.position(80)
    val count = bb.int.toLong() and 0xFFFFFFFFL
    val expected = 84L + count * 50L
    if (expected == bytes.size.toLong() && count in 1..20_000_000) {
        val tris = ArrayList<Tri>(count.toInt())
        bb.position(84)
        repeat(count.toInt()) {
            val nx = bb.float; val ny = bb.float; val nz = bb.float
            val ax = bb.float; val ay = bb.float; val az = bb.float
            val b1 = bb.float; val b2 = bb.float; val b3 = bb.float
            val c1 = bb.float; val c2 = bb.float; val c3 = bb.float
            bb.short // attribute byte count
            val a = Vec3(ax, ay, az); val b = Vec3(b1, b2, b3); val c = Vec3(c1, c2, c3)
            var n = Vec3(nx, ny, nz)
            if (n.length() < 1e-6f) n = (b - a).cross(c - a).normalized()
            tris.add(Tri(a, b, c, n))
        }
        return if (tris.isEmpty()) null else StlModel(tris)
    }
    return parseAsciiStl(bytes)
}

private fun parseAsciiStl(bytes: ByteArray): StlModel? {
    val text = try { String(bytes, Charsets.US_ASCII) } catch (e: Exception) { return null }
    if (!text.trimStart().startsWith("solid")) return null
    val tris = ArrayList<Tri>()
    var n = Vec3(0f, 0f, 1f)
    val verts = ArrayList<Vec3>(3)
    for (raw in text.lineSequence()) {
        val line = raw.trim()
        when {
            line.startsWith("facet normal") -> {
                val p = line.split(Regex("\\s+"))
                if (p.size >= 5) n = Vec3(p[2].toFloatOrNull() ?: 0f, p[3].toFloatOrNull() ?: 0f, p[4].toFloatOrNull() ?: 1f)
                verts.clear()
            }
            line.startsWith("vertex") -> {
                val p = line.split(Regex("\\s+"))
                if (p.size >= 4) verts.add(Vec3(p[1].toFloatOrNull() ?: 0f, p[2].toFloatOrNull() ?: 0f, p[3].toFloatOrNull() ?: 0f))
            }
            line.startsWith("endfacet") -> {
                if (verts.size == 3) {
                    var nn = n
                    if (nn.length() < 1e-6f) nn = (verts[1] - verts[0]).cross(verts[2] - verts[0]).normalized()
                    tris.add(Tri(verts[0], verts[1], verts[2], nn))
                }
            }
        }
    }
    return if (tris.isEmpty()) null else StlModel(tris)
}

/** Wählt anhand Dateiname/Inhalt den passenden Parser (STL/OBJ/3MF). */
private fun parseModel(bytes: ByteArray, name: String?): StlModel? {
    val ext = name?.substringAfterLast('.', "")?.lowercase()
    return when (ext) {
        "obj" -> parseObj(bytes)
        "3mf" -> parse3mf(bytes)
        "stl" -> parseStl(bytes)
        else -> {
            // Unbekannte Endung: Inhalt heuristisch erkennen.
            if (bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte())
                parse3mf(bytes)
            else parseObj(bytes) ?: parseStl(bytes)
        }
    }
}

/** Parst Wavefront-OBJ (nur v/f, Polygone werden fächerförmig trianguliert). */
private fun parseObj(bytes: ByteArray): StlModel? {
    val text = try { String(bytes, Charsets.UTF_8) } catch (e: Exception) { return null }
    val verts = ArrayList<Vec3>()
    val tris = ArrayList<Tri>()
    for (raw in text.lineSequence()) {
        val line = raw.trim()
        when {
            line.startsWith("v ") -> {
                val p = line.split(Regex("\\s+"))
                if (p.size >= 4) verts.add(Vec3(
                    p[1].toFloatOrNull() ?: 0f,
                    p[2].toFloatOrNull() ?: 0f,
                    p[3].toFloatOrNull() ?: 0f
                ))
            }
            line.startsWith("f ") -> {
                val p = line.split(Regex("\\s+"))
                // Index aus "i", "i/j", "i//k", "i/j/k"; OBJ ist 1-basiert, negative = relativ.
                val idx = ArrayList<Int>(p.size - 1)
                for (k in 1 until p.size) {
                    val tok = p[k].substringBefore('/').toIntOrNull() ?: continue
                    val resolved = if (tok < 0) verts.size + tok else tok - 1
                    if (resolved in verts.indices) idx.add(resolved)
                }
                // Fan-Triangulierung
                for (k in 1 until idx.size - 1) {
                    val a = verts[idx[0]]; val b = verts[idx[k]]; val c = verts[idx[k + 1]]
                    val n = (b - a).cross(c - a).normalized()
                    tris.add(Tri(a, b, c, n))
                }
            }
        }
    }
    return if (tris.isEmpty()) null else StlModel(tris)
}

/** Parst 3MF (ZIP-Container) – liest die erste *.model und extrahiert mesh-Geometrie. */
private fun parse3mf(bytes: ByteArray): StlModel? {
    val xml = try {
        java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            var found: ByteArray? = null
            while (entry != null) {
                if (!entry.isDirectory && entry.name.lowercase().endsWith(".model")) {
                    found = zip.readBytes()
                    break
                }
                entry = zip.nextEntry
            }
            found
        }?.let { String(it, Charsets.UTF_8) }
    } catch (e: Exception) { null } ?: return null

    // Vertices: <vertex x=".." y=".." z=".." />
    val verts = ArrayList<Vec3>()
    val vRe = Regex("<vertex\\b[^>]*?x=\"([-0-9.eE]+)\"[^>]*?y=\"([-0-9.eE]+)\"[^>]*?z=\"([-0-9.eE]+)\"")
    for (m in vRe.findAll(xml)) {
        verts.add(Vec3(
            m.groupValues[1].toFloatOrNull() ?: 0f,
            m.groupValues[2].toFloatOrNull() ?: 0f,
            m.groupValues[3].toFloatOrNull() ?: 0f
        ))
    }
    if (verts.isEmpty()) return null

    // Triangles: <triangle v1=".." v2=".." v3=".." />
    val tris = ArrayList<Tri>()
    val tRe = Regex("<triangle\\b[^>]*?v1=\"(\\d+)\"[^>]*?v2=\"(\\d+)\"[^>]*?v3=\"(\\d+)\"")
    for (m in tRe.findAll(xml)) {
        val i1 = m.groupValues[1].toIntOrNull() ?: continue
        val i2 = m.groupValues[2].toIntOrNull() ?: continue
        val i3 = m.groupValues[3].toIntOrNull() ?: continue
        if (i1 in verts.indices && i2 in verts.indices && i3 in verts.indices) {
            val a = verts[i1]; val b = verts[i2]; val c = verts[i3]
            val n = (b - a).cross(c - a).normalized()
            tris.add(Tri(a, b, c, n))
        }
    }
    return if (tris.isEmpty()) null else StlModel(tris)
}

/** Skaliert die komplette Geometrie um einen Faktor (Normalen bleiben bei uniformer Skalierung gleich). */
private fun scaleModel(model: StlModel, f: Float): StlModel {
    if (f == 1f || model.tris.isEmpty()) return model
    return StlModel(model.tris.map { Tri(it.a * f, it.b * f, it.c * f, it.n) })
}

/**
 * Vereinfacht das Mesh per Vertex-Clustering: Vertices werden auf ein Gitter
 * gerundet, dadurch fallen benachbarte Punkte zusammen und Dreiecke kollabieren.
 * Im Gegensatz zum Wegwerfen einzelner Dreiecke entstehen KEINE Löcher.
 * level 0 = nicht vereinfachen, level 10 = sehr stark (grobes Gitter).
 */
private fun simplifyModel(model: StlModel, level: Int): StlModel {
    if (level <= 0 || model.tris.isEmpty()) return model
    val size = model.max - model.min
    val maxDim = max(size.x, max(size.y, size.z))
    if (maxDim < 1e-4f) return model
    // Zellgröße wächst mit dem Level: Level 1 ~0,4 % der Modellgröße (fein),
    // Level 10 ~4 % (≈ 25 Zellen über die größte Achse → deutlich gröber).
    val cell = maxDim * (0.004f * level)
    fun snap(v: Vec3) = Vec3(
        Math.round(v.x / cell) * cell,
        Math.round(v.y / cell) * cell,
        Math.round(v.z / cell) * cell
    )
    val out = ArrayList<Tri>(model.tris.size)
    for (t in model.tris) {
        val a = snap(t.a); val b = snap(t.b); val c = snap(t.c)
        // Dreieck zu einer Linie/Punkt kollabiert → weglassen (degeneriert).
        if (a == b || b == c || a == c) continue
        var n = (b - a).cross(c - a)
        n = if (n.length() < 1e-6f) t.n else n.normalized()
        out.add(Tri(a, b, c, n))
    }
    return if (out.isEmpty()) model else StlModel(out)
}

/** Ermittelt eine Rotation, die die flächengrößte Modellseite nach unten (-Z) legt. */
private fun autoAlignRotation(model: StlModel): FloatArray {
    if (model.tris.isEmpty()) return matIdentity()
    val buckets = HashMap<Triple<Int, Int, Int>, Pair<Float, Vec3>>()
    for (t in model.tris) {
        val nn = t.n.normalized()
        val key = Triple((nn.x * 20).toInt(), (nn.y * 20).toInt(), (nn.z * 20).toInt())
        val area = t.area()
        val cur = buckets[key]
        buckets[key] = if (cur == null) area to (nn * area)
        else (cur.first + area) to (cur.second + nn * area)
    }
    val dominant = buckets.values.maxByOrNull { it.first } ?: return matIdentity()
    val n = dominant.second.normalized()
    val target = Vec3(0f, 0f, -1f)
    val d = n.dot(target).coerceIn(-1f, 1f)
    if (d > 0.9999f) return matIdentity()
    if (d < -0.9999f) return rotAxis(Vec3(1f, 0f, 0f), Math.PI.toFloat())
    val axis = n.cross(target)
    val angle = kotlin.math.acos(d)
    return rotAxis(axis, angle)
}

// ── projizierte Geometrie (für Zeichnen + Picking) ───────────────────────────

// Projektion in primitiven Arrays statt ~450k Objekten (ProjTri/Pair/Vec3) – das frühere
// objektbasierte Design sprengte bei großen Meshes den Heap (OutOfMemoryError).
private class Projection(
    val triXY: FloatArray,   // 6 Floats je Dreieck: p0x,p0y,p1x,p1y,p2x,p2y
    val triColor: IntArray,  // ARGB je Dreieck
    val order: IntArray,     // Dreiecks-Indizes hinten→vorne (Painter's algorithm)
    val triCount: Int,
    val pickSX: FloatArray, val pickSY: FloatArray,                  // Bildschirm-Centroid
    val pickWX: FloatArray, val pickWY: FloatArray, val pickWZ: FloatArray, // Welt-Centroid
    val worldToScreen: (Vec3) -> Offset,
    val pxPerMm: Float                    // orthografischer Maßstab (für Stützen-Dicke)
)

private const val BED_X = 220f
private const val BED_Y = 220f

private fun computeProjection(
    model: StlModel,
    modelRot: FloatArray,
    scale: Float,
    azDeg: Float,
    elDeg: Float,
    viewZoom: Float,
    size: IntSize
): Projection? {
    if (size.width == 0 || size.height == 0 || model.tris.isEmpty()) return null

    // ALLE Dreiecke rendern – Ausdünnen riss Löcher ins Mesh (sah aus wie Drahtgitter).
    val renderTris = model.tris
    val n = renderTris.size

    fun tf(v: Vec3) = matVec(modelRot, (v - model.center)) * scale

    // 1) Absenk-/Zentrier-Offset bestimmen, OHNE die transformierten Vertices zu speichern.
    //    (Das frühere Zwischen-Array mit ~450k Triple<Vec3> + Normalen sprengte den Heap → OOM.)
    var mnz = Float.MAX_VALUE
    var sumX = 0f; var sumY = 0f
    for (t in renderTris) {
        val a = tf(t.a); val b = tf(t.b); val c = tf(t.c)
        mnz = min(mnz, min(a.z, min(b.z, c.z)))
        sumX += a.x + b.x + c.x; sumY += a.y + b.y + c.y
    }
    val cnt = n * 3
    val drop = Vec3(-sumX / cnt, -sumY / cnt, -mnz)

    // 2) Kamera-Orbit (Azimut um Z, dann Elevation um X).
    val az = Math.toRadians(azDeg.toDouble()).toFloat()
    val el = Math.toRadians(elDeg.toDouble()).toFloat()
    val cam = matMul(rotAxis(Vec3(1f, 0f, 0f), el), rotAxis(Vec3(0f, 0f, 1f), az))

    // 3) Zoom so wählen, dass das Druckbett komfortabel ins Viewport passt.
    val bedMax = max(BED_X, BED_Y)
    val zoom = (min(size.width, size.height) * 0.78f) / bedMax * viewZoom
    val cx = size.width / 2f
    val cy = size.height * 0.62f // Bett etwas tiefer setzen

    fun project(world: Vec3): Pair<Offset, Float> {
        val v = matVec(cam, world)
        return Offset(cx + v.x * zoom, cy - v.z * zoom) to v.y // depth = v.y (größer = weiter weg)
    }
    // Bett/Stützen liegen bereits im finalen Frame (Modell wurde via drop dorthin
    // gesetzt), daher hier KEIN erneutes drop addieren.
    val worldToScreen: (Vec3) -> Offset = { project(it).first }

    val light = Vec3(-0.35f, -0.45f, 0.82f).normalized()

    // Ergebnis in primitiven Arrays halten – kein Objekt pro Dreieck.
    val triXY = FloatArray(n * 6)
    val triColor = IntArray(n)
    val pickSX = FloatArray(n); val pickSY = FloatArray(n)
    val pickWX = FloatArray(n); val pickWY = FloatArray(n); val pickWZ = FloatArray(n)
    val keys = LongArray(n) // Tiefe(32 Bit, sortierbar) | Index(32 Bit) → Painter-Sort ohne Boxing

    var i = 0
    for (t in renderTris) {
        val a = tf(t.a) + drop; val b = tf(t.b) + drop; val c = tf(t.c) + drop
        val (pa, da) = project(a); val (pb, db) = project(b); val (pc, dc) = project(c)
        val depth = (da + db + dc) / 3f
        val nrm = matVec(modelRot, t.n).normalized()
        val shade = (0.25f + 0.75f * max(0f, nrm.dot(light))).coerceIn(0f, 1f)
        val col = android.graphics.Color.rgb(
            ((0.12f + 0.75f * shade) * 255f).toInt().coerceIn(0, 255),
            ((0.55f + 0.40f * shade) * 255f).toInt().coerceIn(0, 255),
            ((0.05f + 0.20f * shade) * 255f).toInt().coerceIn(0, 255)
        )
        val o = i * 6
        triXY[o] = pa.x; triXY[o + 1] = pa.y
        triXY[o + 2] = pb.x; triXY[o + 3] = pb.y
        triXY[o + 4] = pc.x; triXY[o + 5] = pc.y
        triColor[i] = col
        pickSX[i] = (pa.x + pb.x + pc.x) / 3f
        pickSY[i] = (pa.y + pb.y + pc.y) / 3f
        pickWX[i] = (a.x + b.x + c.x) / 3f
        pickWY[i] = (a.y + b.y + c.y) / 3f
        pickWZ[i] = (a.z + b.z + c.z) / 3f
        val bits = java.lang.Float.floatToRawIntBits(depth)
        val sortable = bits xor ((bits shr 31) or Int.MIN_VALUE) // monoton: größerer Float → größerer Int
        keys[i] = (sortable.toLong() shl 32) or (i.toLong() and 0xFFFFFFFFL)
        i++
    }
    // Painter's algorithm: großes depth (hinten) zuerst zeichnen.
    keys.sort() // aufsteigend nach Tiefe; größte Tiefe steht hinten → beim Befüllen umkehren.
    val order = IntArray(n)
    for (k in 0 until n) order[k] = (keys[n - 1 - k] and 0xFFFFFFFFL).toInt()

    return Projection(
        triXY, triColor, order, n,
        pickSX, pickSY, pickWX, pickWY, pickWZ,
        worldToScreen, zoom
    )
}

/**
 * Rastert alle Modell-Dreiecke EINMAL in ein Off-Screen-Bitmap (Hintergrund-Thread).
 * Große Meshes (z. B. 450k Flächen) dürfen NICHT pro Frame auf dem UI-Thread gezeichnet
 * werden – das blockiert den Main-Thread → ANR. Der Compose-Canvas blittet danach nur
 * dieses eine Bitmap. Ein einzelner wiederverwendeter android.graphics.Path vermeidet
 * Hunderttausende Allokationen.
 */
private fun renderModelBitmap(proj: Projection, width: Int, height: Int): ImageBitmap? {
    if (width <= 0 || height <= 0) return null
    val bmp = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.FILL
    }
    val path = android.graphics.Path()
    val xy = proj.triXY
    for (idx in proj.order) {
        val o = idx * 6
        path.rewind()
        path.moveTo(xy[o], xy[o + 1])
        path.lineTo(xy[o + 2], xy[o + 3])
        path.lineTo(xy[o + 4], xy[o + 5])
        path.close()
        paint.color = proj.triColor[idx]
        canvas.drawPath(path, paint)
    }
    return bmp.asImageBitmap()
}

// ── Screen ───────────────────────────────────────────────────────────────────

private enum class SlicerTool { NONE, SUPPORT, TRANSFORM }
private val PROFILE_TYPES = listOf("printer", "process", "filament")
private fun profileDir(ctx: android.content.Context, type: String) =
    File(ctx.filesDir, "slicer_profiles/$type").apply { mkdirs() }

/** Parameter für organische Baum-Stützen – aus dem Process-Profil oder mit Defaults. */
private data class SupportProfile(
    val tipDiameter: Float,    // mm – schmale Spitze am Modell
    val branchDiameter: Float, // mm – dicker Stamm am Bett
    val xyGap: Float           // mm – Abstand der Spitze zum Objekt
) {
    companion object { val DEFAULT = SupportProfile(0.8f, 4.0f, 1.0f) }
}

/** Liest tree_support_*-Werte aus dem ersten Process-Profil; fehlt etwas, greifen Defaults. */
private fun loadSupportProfile(ctx: android.content.Context): SupportProfile {
    val def = SupportProfile.DEFAULT
    val f = profileDir(ctx, "process").listFiles()
        ?.firstOrNull { it.extension == "json" } ?: return def
    return try {
        val o = org.json.JSONObject(f.readText())
        // Orca speichert Werte als Strings (teils Arrays) – defensiv parsen.
        fun num(vararg keys: String): Float? {
            for (k in keys) {
                val s = o.opt(k) ?: continue
                val str = when (s) {
                    is org.json.JSONArray -> if (s.length() > 0) s.optString(0) else null
                    else -> s.toString()
                } ?: continue
                str.trim().removeSuffix("%").toFloatOrNull()?.let { return it }
            }
            return null
        }
        SupportProfile(
            tipDiameter = num("tree_support_tip_diameter")?.coerceAtLeast(0.2f) ?: def.tipDiameter,
            branchDiameter = num("tree_support_branch_diameter")?.coerceAtLeast(0.5f) ?: def.branchDiameter,
            xyGap = num("support_object_xy_distance", "support_object_first_layer_gap")
                ?.coerceAtLeast(1.0f) ?: def.xyGap
        )
    } catch (e: Exception) { def }
}

// ── Projekt-Verwaltung (virtuelles Druckbett mit allem drauf speichern/laden) ─

private fun projectsDir(ctx: android.content.Context) =
    File(ctx.filesDir, "slicer_projects").apply { mkdirs() }

private fun listProjects(ctx: android.content.Context): List<File> =
    projectsDir(ctx).listFiles()?.filter { it.extension == "json" }
        ?.sortedByDescending { it.lastModified() } ?: emptyList()

private fun sanitizeFileName(name: String): String =
    name.trim().ifBlank { "projekt" }.replace(Regex("[^A-Za-z0-9 _-]"), "_").take(60)

/** Gesamter Bett-Zustand: Modell-Mesh + Transform + Stützpunkte + Kamera. */
private class SlicerProject(
    val name: String,
    val model: StlModel,
    val modelRot: FloatArray,
    val scale: Float,
    val simplify: Int,
    val az: Float,
    val el: Float,
    val zoom: Float,
    val supports: List<Vec3>
)

/** Serialisiert den kompletten Bett-Zustand als JSON in filesDir/slicer_projects. */
private fun saveProject(
    ctx: android.content.Context,
    name: String,
    model: StlModel,
    rot: FloatArray,
    scale: Float,
    simplify: Int,
    az: Float, el: Float, zoom: Float,
    supports: List<Vec3>
): Boolean = try {
    val o = org.json.JSONObject()
    o.put("name", name)
    o.put("scale", scale.toDouble())
    o.put("simplify", simplify)
    o.put("az", az.toDouble()); o.put("el", el.toDouble()); o.put("zoom", zoom.toDouble())
    val rotArr = org.json.JSONArray(); rot.forEach { rotArr.put(it.toDouble()) }
    o.put("rot", rotArr)
    val sup = org.json.JSONArray()
    supports.forEach { v ->
        sup.put(org.json.JSONArray().put(v.x.toDouble()).put(v.y.toDouble()).put(v.z.toDouble()))
    }
    o.put("supports", sup)
    // Mesh: pro Dreieck die 12 Floats a(xyz) b(xyz) c(xyz) n(xyz).
    val tris = org.json.JSONArray()
    for (t in model.tris) {
        val ta = org.json.JSONArray()
        floatArrayOf(
            t.a.x, t.a.y, t.a.z, t.b.x, t.b.y, t.b.z,
            t.c.x, t.c.y, t.c.z, t.n.x, t.n.y, t.n.z
        ).forEach { ta.put(it.toDouble()) }
        tris.put(ta)
    }
    o.put("tris", tris)
    File(projectsDir(ctx), sanitizeFileName(name) + ".json").writeText(o.toString())
    true
} catch (e: Exception) { false }

/** Liest einen gespeicherten Bett-Zustand zurück. */
private fun loadProject(f: File): SlicerProject? {
    return try {
        val o = org.json.JSONObject(f.readText())
        val rotArr = o.getJSONArray("rot")
        val rot = FloatArray(9) { rotArr.getDouble(it).toFloat() }
        val triArr = o.getJSONArray("tris")
        val tris = ArrayList<Tri>(triArr.length())
        for (i in 0 until triArr.length()) {
            val a = triArr.getJSONArray(i)
            tris.add(Tri(
                Vec3(a.getDouble(0).toFloat(), a.getDouble(1).toFloat(), a.getDouble(2).toFloat()),
                Vec3(a.getDouble(3).toFloat(), a.getDouble(4).toFloat(), a.getDouble(5).toFloat()),
                Vec3(a.getDouble(6).toFloat(), a.getDouble(7).toFloat(), a.getDouble(8).toFloat()),
                Vec3(a.getDouble(9).toFloat(), a.getDouble(10).toFloat(), a.getDouble(11).toFloat())
            ))
        }
        if (tris.isEmpty()) return null
        val sup = o.optJSONArray("supports")
        val supports = ArrayList<Vec3>()
        if (sup != null) for (i in 0 until sup.length()) {
            val s = sup.getJSONArray(i)
            supports.add(Vec3(s.getDouble(0).toFloat(), s.getDouble(1).toFloat(), s.getDouble(2).toFloat()))
        }
        SlicerProject(
            name = o.optString("name", f.nameWithoutExtension),
            model = StlModel(tris),
            modelRot = rot,
            scale = o.optDouble("scale", 1.0).toFloat(),
            simplify = o.optInt("simplify", 0),
            az = o.optDouble("az", 35.0).toFloat(),
            el = o.optDouble("el", 60.0).toFloat(),
            zoom = o.optDouble("zoom", 1.0).toFloat(),
            supports = supports
        )
    } catch (e: Exception) { null }
}

@Composable
fun SlicerScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var model by remember { mutableStateOf<StlModel?>(null) }
    var modelName by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    var tool by remember { mutableStateOf(SlicerTool.NONE) }
    var modelRot by remember { mutableStateOf(matIdentity()) }
    var scaleVal by remember { mutableStateOf(1f) }
    var simplifyLevel by remember { mutableStateOf(0) } // 0 = nicht, 10 = sehr stark vereinfachen
    var az by remember { mutableStateOf(35f) }
    var el by remember { mutableStateOf(60f) }
    var viewZoom by remember { mutableStateOf(1f) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val supports = remember { mutableStateListOf<Vec3>() }
    var supportDelete by remember { mutableStateOf(false) } // im Stützen-Modus: tippen entfernt statt setzt

    var showProfiles by remember { mutableStateOf(false) }
    var showProjectMenu by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var showSimplifyDialog by remember { mutableStateOf(false) } // nach Modell-Laden: wie stark vereinfachen?
    var projectsRefresh by remember { mutableStateOf(0) }
    // Baum-Stützen-Parameter aus dem Process-Profil (nach Dialog-Schließen neu laden).
    var supportProfile by remember { mutableStateOf(SupportProfile.DEFAULT) }
    LaunchedEffect(showProfiles) {
        if (!showProfiles) supportProfile = withContext(Dispatchers.IO) { loadSupportProfile(context) }
    }

    // Kollisionsgitter: wird im Hintergrund gebaut sobald Modell/Rotation/Skalierung sich ändert.
    var collider by remember { mutableStateOf<SupportCollider?>(null) }
    // Stützen-Segmente: werden neu berechnet wenn Stützen, Gitter oder Profil sich ändern.
    var treeSegs by remember { mutableStateOf<List<TreeSeg>>(emptyList()) }
    LaunchedEffect(model, modelRot, scaleVal) {
        val m = model ?: run { collider = null; return@LaunchedEffect }
        collider = withContext(Dispatchers.Default) { buildSupportCollider(m, modelRot, scaleVal) }
    }
    val supportsSnapshot = supports.toList()
    LaunchedEffect(supportsSnapshot, collider, supportProfile) {
        treeSegs = withContext(Dispatchers.Default) {
            buildTreeSupports(supportsSnapshot, collider, supportProfile)
        }
    }

    // STL-Picker
    val stlPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        loading = true
        scope.launch {
            val name = withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                        val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
                    }
                } catch (e: Exception) { null }
            }
            var autoScaledTo: Float? = null
            val parsed = withContext(Dispatchers.IO) {
                val m = try {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }?.let { parseModel(it, name) }
                } catch (e: Exception) { null } ?: return@withContext null
                // OBJ ist einheitenlos – manche Exporte liefern winzige (z. B. Meter/Dezimeter)
                // oder riesige Koordinaten. Solche Modelle würden als Punkt bzw. weit außerhalb
                // des Betts erscheinen und ließen sich mit dem 0,1–3×-Regler nicht korrigieren.
                // Daher unplausible OBJ-Größen beim Laden aufs Bett normalisieren (in die Geometrie
                // eingerechnet → Maße, Regler und Stützen arbeiten danach wieder normal in mm).
                val ext = m.max - m.min
                val maxDim = max(ext.x, max(ext.y, ext.z))
                val isObj = name?.substringAfterLast('.', "")?.lowercase() == "obj"
                if (isObj && maxDim > 1e-4f && (maxDim < 10f || maxDim > BED_X * 1.5f)) {
                    val factor = (min(BED_X, BED_Y) * 0.8f) / maxDim
                    autoScaledTo = factor
                    scaleModel(m, factor)
                } else m
            }
            loading = false
            if (parsed == null) {
                Toast.makeText(context, "Modell konnte nicht gelesen werden (STL/OBJ/3MF)", Toast.LENGTH_SHORT).show()
            } else {
                model = parsed
                modelName = name ?: "modell"
                modelRot = matIdentity(); scaleVal = 1f; simplifyLevel = 0; viewZoom = 1f; supports.clear()
                if (autoScaledTo != null) {
                    Toast.makeText(context, "OBJ ohne Maßeinheit – automatisch aufs Bett skaliert. Größe im Skalieren-Werkzeug anpassbar.", Toast.LENGTH_LONG).show()
                }
                showSimplifyDialog = true
            }
        }
    }

    // Vereinfachtes Modell (Vertex-Clustering) – nur neu berechnen wenn nötig.
    val displayModel by produceState<StlModel?>(null, model, simplifyLevel) {
        val m = model
        value = if (m == null) null
        else withContext(Dispatchers.Default) { simplifyModel(m, simplifyLevel) }
    }

    val projection by produceState<Projection?>(null, displayModel, modelRot, scaleVal, az, el, viewZoom, viewport) {
        val m = displayModel
        if (m == null || viewport.width == 0 || viewport.height == 0) { value = null; return@produceState }
        val rotSnapshot = modelRot.copyOf() // FloatArray vor Background-Zugriff kopieren
        value = withContext(Dispatchers.Default) {
            computeProjection(m, rotSnapshot, scaleVal, az, el, viewZoom, viewport)
        }
    }

    // Modell-Dreiecke werden im Hintergrund in ein Bitmap gerastert (kein Zeichnen
    // von Hunderttausenden Pfaden auf dem UI-Thread → verhindert ANR). Der vorherige
    // Wert bleibt sichtbar, bis das neue Bitmap fertig ist (kein Flackern beim Orbit).
    val modelBitmap by produceState<ImageBitmap?>(null, projection) {
        val p = projection
        value = if (p == null) null
        else withContext(Dispatchers.Default) { renderModelBitmap(p, viewport.width, viewport.height) }
    }

    // Maße (B/T/H in mm) des gedrehten, skalierten Modells – im Hintergrund berechnet.
    // Triple = (Breite X, Tiefe Y, Höhe Z) nach Rotation, multipliziert mit der Skalierung.
    val modelDims by produceState<Triple<Float, Float, Float>?>(null, displayModel, modelRot, scaleVal) {
        val m = displayModel
        if (m == null) { value = null; return@produceState }
        val rot = modelRot.copyOf()
        value = withContext(Dispatchers.Default) {
            var mnx = Float.MAX_VALUE; var mny = Float.MAX_VALUE; var mnz = Float.MAX_VALUE
            var mxx = -Float.MAX_VALUE; var mxy = -Float.MAX_VALUE; var mxz = -Float.MAX_VALUE
            for (t in m.tris) {
                for (v in arrayOf(t.a, t.b, t.c)) {
                    val r = matVec(rot, v)
                    if (r.x < mnx) mnx = r.x; if (r.x > mxx) mxx = r.x
                    if (r.y < mny) mny = r.y; if (r.y > mxy) mxy = r.y
                    if (r.z < mnz) mnz = r.z; if (r.z > mxz) mxz = r.z
                }
            }
            Triple((mxx - mnx) * scaleVal, (mxy - mny) * scaleVal, (mxz - mnz) * scaleVal)
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(BackgroundDark)) {

        // ── schmale Topbar ──────────────────────────────────────────────────
        Surface(color = SurfaceDark, shadowElevation = 8.dp) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .height(46.dp)
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TopBarIcon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück", false) { onNavigateBack() }
                Text(
                    "Slicen",
                    color = OnSurface,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 2.dp)
                )
                Spacer(Modifier.weight(1f))
                TopBarIcon(Icons.Default.Forest, "Stützen (Baum)", tool == SlicerTool.SUPPORT) {
                    tool = if (tool == SlicerTool.SUPPORT) SlicerTool.NONE else SlicerTool.SUPPORT
                }
                TopBarIcon(Icons.Default.VerticalAlignBottom, "Auto-Ausrichtung", false, enabled = model != null) {
                    model?.let { modelRot = autoAlignRotation(it); supports.clear() }
                }
                TopBarIcon(Icons.Default.Transform, "Skalieren / Drehen", tool == SlicerTool.TRANSFORM) {
                    tool = if (tool == SlicerTool.TRANSFORM) SlicerTool.NONE else SlicerTool.TRANSFORM
                }
                Box {
                    TopBarIcon(Icons.Default.Save, "Projekte", showProjectMenu) { showProjectMenu = true }
                    DropdownMenu(
                        expanded = showProjectMenu,
                        onDismissRequest = { showProjectMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Projekt speichern") },
                            enabled = model != null,
                            leadingIcon = { Icon(Icons.Default.Save, null, modifier = Modifier.size(18.dp)) },
                            onClick = { showProjectMenu = false; showSaveDialog = true }
                        )
                        HorizontalDivider()
                        Text(
                            "Projekt laden",
                            color = OnSurfaceDim,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                        )
                        val projects = key(projectsRefresh) { listProjects(context) }
                        if (projects.isEmpty()) {
                            DropdownMenuItem(text = { Text("— keine —", color = OnSurfaceDim) }, enabled = false, onClick = {})
                        } else {
                            projects.forEach { f ->
                                DropdownMenuItem(
                                    text = { Text(f.nameWithoutExtension, maxLines = 1) },
                                    trailingIcon = {
                                        Icon(
                                            Icons.Default.Delete, "Löschen", tint = ErrorRed,
                                            modifier = Modifier.size(18.dp).clickable { f.delete(); projectsRefresh++ }
                                        )
                                    },
                                    onClick = {
                                        showProjectMenu = false
                                        loading = true
                                        scope.launch {
                                            val proj = withContext(Dispatchers.IO) { loadProject(f) }
                                            loading = false
                                            if (proj == null) {
                                                Toast.makeText(context, "Projekt konnte nicht geladen werden", Toast.LENGTH_SHORT).show()
                                            } else {
                                                model = proj.model
                                                modelName = proj.name
                                                modelRot = proj.modelRot
                                                scaleVal = proj.scale
                                                simplifyLevel = proj.simplify
                                                az = proj.az; el = proj.el; viewZoom = proj.zoom
                                                supports.clear(); supports.addAll(proj.supports)
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
                TopBarIcon(Icons.Default.MoreVert, "Profile", showProfiles) { showProfiles = true }
            }
        }

        // ── Viewport ────────────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(BackgroundDark)
                .onSizeChanged { viewport = it }
                // EIN gemeinsamer pointerInput pro Werkzeug. Zwei separate pointerInput
                // (einer mit detectTransformGestures/detectDragGestures, einer mit
                // detectTapGestures) konkurrierten um dieselben Events → im Stützen-Modus
                // kamen Taps nicht an. Jetzt ist je Modus genau EIN Detektor aktiv.
                .pointerInput(tool, supportDelete, projection, model) {
                    when (tool) {
                        SlicerTool.SUPPORT -> {
                            // Tippen setzt bzw. entfernt Stützpunkte (kein Orbit/Zoom).
                            detectTapGestures { offset ->
                                val proj = projection ?: return@detectTapGestures
                                if (supportDelete) {
                                    // Nächstgelegene gesetzte Stütze entfernen.
                                    var bestIdx = -1; var bestD = Float.MAX_VALUE
                                    supports.forEachIndexed { i, w ->
                                        val s = proj.worldToScreen(w)
                                        val dx = s.x - offset.x; val dy = s.y - offset.y
                                        val d = dx * dx + dy * dy
                                        if (d < bestD) { bestD = d; bestIdx = i }
                                    }
                                    if (bestIdx >= 0 && bestD < 60f * 60f) supports.removeAt(bestIdx)
                                } else {
                                    // Nächstgelegenen projizierten Dreiecks-Mittelpunkt picken.
                                    var best: Vec3? = null; var bestD = Float.MAX_VALUE
                                    for (k in 0 until proj.triCount) {
                                        val dx = proj.pickSX[k] - offset.x; val dy = proj.pickSY[k] - offset.y
                                        val d = dx * dx + dy * dy
                                        if (d < bestD) { bestD = d; best = Vec3(proj.pickWX[k], proj.pickWY[k], proj.pickWZ[k]) }
                                    }
                                    if (best != null && bestD < 60f * 60f) supports.add(best)
                                }
                            }
                        }
                        SlicerTool.NONE -> {
                            // Kein Werkzeug aktiv: Orbit (Ziehen) + Pinch-Zoom.
                            detectTransformGestures { _, pan, gestureZoom, _ ->
                                az += pan.x * 0.4f
                                el = (el + pan.y * 0.4f).coerceIn(5f, 89f)
                                viewZoom = (viewZoom * gestureZoom).coerceIn(0.3f, 6f)
                            }
                        }
                        SlicerTool.TRANSFORM -> {
                            detectDragGestures { _, drag ->
                                az += drag.x * 0.4f
                                el = (el + drag.y * 0.4f).coerceIn(5f, 89f)
                            }
                        }
                    }
                }
        ) {
            SlicerCanvas(projection = projection, modelBitmap = modelBitmap, treeSegs = treeSegs)

            // Maße oben links (grau, untereinander) zum geladenen Modell.
            modelDims?.let { (w, d, h) ->
                Column(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 12.dp, top = 10.dp)
                ) {
                    Text("Breite: %.1f mm".format(w), color = OnSurfaceDim, fontSize = 12.sp)
                    Text("Tiefe: %.1f mm".format(d), color = OnSurfaceDim, fontSize = 12.sp)
                    Text("Höhe: %.1f mm".format(h), color = OnSurfaceDim, fontSize = 12.sp)
                }
            }

            if (model == null && !loading) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(Icons.Default.UploadFile, null, tint = OnSurfaceDim, modifier = Modifier.size(48.dp))
                    Text("Kein Modell geladen", color = OnSurfaceDim, fontSize = 14.sp)
                    Text("STL · OBJ · 3MF", color = OnSurfaceDim, fontSize = 11.sp)
                    Button(
                        onClick = { stlPicker.launch("*/*") },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentYellow)
                    ) { Text("Modell laden", color = Color.Black) }
                }
            }
            if (loading) {
                CircularProgressIndicator(color = AccentYellow, modifier = Modifier.align(Alignment.Center))
            }

            // Stützen-Hinweis + Hinzufügen/Löschen-Umschalter
            if (tool == SlicerTool.SUPPORT && model != null) {
                Column(
                    modifier = Modifier.align(Alignment.TopCenter).padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        if (supportDelete)
                            "Tippe auf eine Stütze, um sie zu löschen (${supports.size})"
                        else
                            "Tippe auf das Modell, um Stützpunkte zu setzen (${supports.size})",
                        color = Color.Black,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .background(AccentYellow, RoundedCornerShape(6.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SupportModeChip("Hinzufügen", !supportDelete) { supportDelete = false }
                        SupportModeChip("Löschen", supportDelete) { supportDelete = true }
                    }
                }
            }

            // Transform-Panel
            if (tool == SlicerTool.TRANSFORM && model != null) {
                TransformPanel(
                    scale = scaleVal,
                    onScale = { scaleVal = it; supports.clear() },
                    simplifyLevel = simplifyLevel,
                    onSimplify = { simplifyLevel = it; supports.clear() },
                    onRotate = { axis ->
                        val r = rotAxis(axis, (Math.PI / 2).toFloat())
                        modelRot = matMul(r, modelRot); supports.clear()
                    },
                    onReset = { modelRot = matIdentity(); scaleVal = 1f; simplifyLevel = 0; viewZoom = 1f; supports.clear() },
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
        }

        // ── untere Aktionsleiste ─────────────────────────────────────────────
        Surface(color = SurfaceDark, shadowElevation = 12.dp) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Text(modelName ?: "—", color = OnSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                    val info = model?.let {
                        val s = it.max - it.min
                        val faces = displayModel?.tris?.size ?: it.tris.size
                        val simpl = if (simplifyLevel > 0) " · vereinf. $simplifyLevel" else ""
                        "$faces Flächen$simpl · ${"%.0f×%.0f×%.0f".format(s.x * scaleVal, s.y * scaleVal, s.z * scaleVal)} mm"
                    } ?: "kein Modell"
                    Text(info, color = OnSurfaceDim, fontSize = 11.sp, maxLines = 1)
                }
                if (model != null) {
                    OutlinedButton(onClick = { stlPicker.launch("*/*") }) {
                        Text("Modell wechseln", fontSize = 12.sp)
                    }
                }
                Button(
                    onClick = { },
                    enabled = false, // Slice-Engine noch nicht angebunden – Button bewusst ausgegraut
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AccentYellow,
                        contentColor = Color.Black,
                        disabledContainerColor = AccentYellow.copy(alpha = 0.35f),
                        disabledContentColor = Color.Black.copy(alpha = 0.45f)
                    )
                ) { Text("Slicen", fontWeight = FontWeight.SemiBold) }
            }
        }
    }

    if (showProfiles) {
        ProfilesDialog(onDismiss = { showProfiles = false })
    }

    if (showSaveDialog) {
        var projName by remember { mutableStateOf(modelName?.substringBeforeLast('.') ?: "projekt") }
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            containerColor = SurfaceDark,
            title = { Text("Projekt speichern", color = OnSurface) },
            text = {
                OutlinedTextField(
                    value = projName,
                    onValueChange = { projName = it },
                    label = { Text("Name") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    enabled = projName.isNotBlank(),
                    onClick = {
                        val m = model
                        showSaveDialog = false
                        if (m != null) scope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                saveProject(context, projName, m, modelRot, scaleVal, simplifyLevel, az, el, viewZoom, supports.toList())
                            }
                            projectsRefresh++
                            Toast.makeText(
                                context,
                                if (ok) "Projekt gespeichert" else "Speichern fehlgeschlagen",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                ) { Text("Speichern", color = AccentYellow) }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false }) { Text("Abbrechen", color = OnSurfaceDim) }
            }
        )
    }

    if (showSimplifyDialog) {
        var lvl by remember { mutableStateOf(simplifyLevel.toFloat()) }
        AlertDialog(
            onDismissRequest = { showSimplifyDialog = false },
            containerColor = SurfaceDark,
            title = { Text("Modell vereinfachen", color = OnSurface) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Wie stark soll das Modell vereinfacht werden?",
                        color = OnSurfaceDim, fontSize = 12.sp
                    )
                    Text(
                        "Vereinfachung: " + if (lvl.toInt() == 0) "aus" else "${lvl.toInt()} / 10",
                        color = OnSurface, fontSize = 13.sp
                    )
                    Slider(
                        value = lvl,
                        onValueChange = { lvl = it },
                        valueRange = 0f..10f,
                        steps = 9,
                        colors = SliderDefaults.colors(thumbColor = AccentYellow, activeTrackColor = AccentYellow)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    simplifyLevel = lvl.toInt().coerceIn(0, 10)
                    supports.clear()
                    showSimplifyDialog = false
                }) { Text("Übernehmen", color = AccentYellow) }
            },
            dismissButton = {
                TextButton(onClick = { showSimplifyDialog = false }) { Text("Abbrechen", color = OnSurfaceDim) }
            }
        )
    }
}

@Composable
private fun SupportModeChip(label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) AccentYellow else SurfaceDark)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (active) Color.Black else OnSurface,
            fontSize = 12.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
private fun TopBarIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    desc: String,
    active: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) AccentYellow.copy(alpha = 0.18f) else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = desc,
            tint = when {
                !enabled -> OnSurfaceDim.copy(alpha = 0.4f)
                active -> AccentYellow
                else -> OnSurface
            },
            modifier = Modifier.size(20.dp)
        )
    }
}

// Ein Segment einer Baum-Stütze in Weltkoordinaten (mit mm-Radien an beiden Enden).
private class TreeSeg(val a: Vec3, val rA: Float, val b: Vec3, val rB: Float)

// ── Stützen-Kollisionsgitter ─────────────────────────────────────────────────

/**
 * 3-D-Belegungsgitter: markiert alle Zellen, die Modell-Geometrie enthalten.
 * Ermöglicht O(1)-Kollisionsprüfung und freie Säulensuche für Stützen-Routing.
 */
private class SupportCollider(
    val xyCell: Float, val zCell: Float,
    val ofsX: Float, val ofsY: Float,
    val nx: Int, val ny: Int, val nz: Int,
    val grid: BooleanArray   // [zi*ny*nx + yi*nx + xi]
) {
    fun isOccupiedAt(x: Float, y: Float, z: Float): Boolean {
        val xi = ((x - ofsX) / xyCell).toInt()
        val yi = ((y - ofsY) / xyCell).toInt()
        val zi = (z / zCell).toInt()
        if (xi < 0 || xi >= nx || yi < 0 || yi >= ny || zi < 0 || zi >= nz) return false
        return grid[zi * ny * nx + yi * nx + xi]
    }

    /**
     * Scannt von fromZ abwärts; gibt Z zurück wo das Modell nach dem Verlassen
     * der Ausgangs-Oberfläche (Spitzenauflage) erneut auftaucht = Hindernis-Oberkante.
     */
    fun firstBlockBelow(x: Float, y: Float, fromZ: Float): Float? {
        var z = fromZ - zCell
        var exitedSurface = false
        while (z > zCell) {
            val occ = isOccupiedAt(x, y, z)
            if (exitedSurface) { if (occ) return z } else { if (!occ) exitedSurface = true }
            z -= zCell
        }
        return null
    }

    /** True wenn die vertikale Säule an (x,y) von z≈0 bis zMax vollständig frei ist. */
    private fun isColumnClear(x: Float, y: Float, zMax: Float): Boolean {
        var z = zCell * 0.5f
        while (z <= zMax + zCell) {
            if (isOccupiedAt(x, y, z)) return false
            z += zCell
        }
        return true
    }

    /**
     * Sucht den nächstgelegenen freien XY-Punkt für eine Säule von 0 bis zMax.
     * Bevorzugt Richtung weg vom Modellzentrum (Ursprung in Welt-Koordinaten).
     */
    fun findClearColumnXY(sx: Float, sy: Float, zMax: Float): Pair<Float, Float>? {
        val len = sqrt(sx * sx + sy * sy)
        val ox = if (len > 0.01f) sx / len else 1f
        val oy = if (len > 0.01f) sy / len else 0f
        val sq2 = sqrt(2f) / 2f
        // 8 Richtungen, sortiert: zuerst auswärts, dann ±45°, ±90°, ±135°, innen
        val dirs = listOf(
            ox to oy,
            (ox - oy) * sq2 to (ox + oy) * sq2,
            (ox + oy) * sq2 to (-ox + oy) * sq2,
            -oy to ox, oy to -ox,
            (-ox - oy) * sq2 to (ox - oy) * sq2,
            (-ox + oy) * sq2 to (-ox - oy) * sq2,
            -ox to -oy
        )
        for (step in 1..60) {
            val dist = step * xyCell
            for ((dx, dy) in dirs) {
                if (isColumnClear(sx + dx * dist, sy + dy * dist, zMax)) return (sx + dx * dist) to (sy + dy * dist)
            }
        }
        return null
    }
}

/** Baut das 3-D-Belegungsgitter aus dem transformierten Modell (Hintergrund-Thread!). */
private fun buildSupportCollider(
    model: StlModel, modelRot: FloatArray, scale: Float,
    xyCell: Float = 2f, zCell: Float = 3f
): SupportCollider {
    fun tf(v: Vec3) = matVec(modelRot, v - model.center) * scale
    // Pass 1: Drop-Offset berechnen (identisch zu computeProjection)
    var mnz = Float.MAX_VALUE; var sumX = 0f; var sumY = 0f
    for (t in model.tris) {
        val a = tf(t.a); val b = tf(t.b); val c = tf(t.c)
        mnz = min(mnz, min(a.z, min(b.z, c.z)))
        sumX += a.x + b.x + c.x; sumY += a.y + b.y + c.y
    }
    val cnt = model.tris.size * 3
    val drop = Vec3(-sumX / cnt, -sumY / cnt, -mnz)
    // Gitter-Dimensionen (bettbezogen; etwas größer als Druckbett)
    val ofsX = -BED_X * 0.7f; val ofsY = -BED_Y * 0.7f
    val nx = (BED_X * 1.4f / xyCell).toInt() + 1
    val ny = (BED_Y * 1.4f / xyCell).toInt() + 1
    val modelExt = model.max - model.min
    val maxH = max(modelExt.x, max(modelExt.y, modelExt.z)) * scale * 1.3f + 20f
    val nz = (maxH / zCell).toInt() + 2
    val grid = BooleanArray(nx * ny * nz)
    // Pass 2: Dreiecke ins Gitter eintragen (konservative AABB-Rasterisierung)
    for (t in model.tris) {
        val a = tf(t.a) + drop; val b = tf(t.b) + drop; val c = tf(t.c) + drop
        val xiLo = max(0, ((min(a.x, min(b.x, c.x)) - ofsX) / xyCell).toInt())
        val xiHi = min(nx - 1, ((max(a.x, max(b.x, c.x)) - ofsX) / xyCell).toInt() + 1)
        val yiLo = max(0, ((min(a.y, min(b.y, c.y)) - ofsY) / xyCell).toInt())
        val yiHi = min(ny - 1, ((max(a.y, max(b.y, c.y)) - ofsY) / xyCell).toInt() + 1)
        val ziLo = max(0, (min(a.z, min(b.z, c.z)) / zCell).toInt())
        val ziHi = min(nz - 1, (max(a.z, max(b.z, c.z)) / zCell).toInt() + 1)
        for (zi in ziLo..ziHi) for (yi in yiLo..yiHi) for (xi in xiLo..xiHi) {
            grid[zi * ny * nx + yi * nx + xi] = true
        }
    }
    return SupportCollider(xyCell, zCell, ofsX, ofsY, nx, ny, nz, grid)
}

/**
 * Berechnet den optimalen Stützen-Weg von der Spitze zum Druckbett.
 * Erkennt Hindernisse unterhalb und biegt den Weg seitlich darum herum.
 */
private fun routeSupportPath(tip: Vec3, collider: SupportCollider?, p: SupportProfile): List<Vec3> {
    val anchorZ = (tip.z - p.xyGap).coerceAtLeast(0f)
    val anchor = Vec3(tip.x, tip.y, anchorZ)
    if (collider == null || anchorZ <= 0f) return listOf(anchor, Vec3(tip.x, tip.y, 0f))
    // Hindernis unterhalb der Spitzenaufhängung suchen
    val blockZ = collider.firstBlockBelow(tip.x, tip.y, anchorZ)
        ?: return listOf(anchor, Vec3(tip.x, tip.y, 0f))   // freie Bahn → gerade
    // Biegepunkt: knapp über dem Hindernis
    val bendZ = (blockZ + p.xyGap * 2f).coerceIn(0f, anchorZ - p.xyGap)
    // Freie vertikale Säule neben dem Hindernis suchen
    val (cx, cy) = collider.findClearColumnXY(tip.x, tip.y, blockZ)
        ?: return listOf(anchor, Vec3(tip.x, tip.y, 0f))   // Fallback gerade
    // Diagonaler Übergangspunkt auf halber Hindernishöhe
    val midZ = (blockZ * 0.5f).coerceAtLeast(collider.zCell)
    return listOf(anchor, Vec3(tip.x, tip.y, bendZ), Vec3(cx, cy, midZ), Vec3(cx, cy, 0f))
}

/** Glättet Eckpunkte einer Wegpunkt-Liste zu sanften quadratischen Bezier-Bögen. */
private fun smoothSupportPath(pts: List<Vec3>, cornerR: Float = 5f): List<Vec3> {
    if (pts.size < 3) return pts
    val out = ArrayList<Vec3>(pts.size * 6); out.add(pts.first())
    for (i in 1 until pts.size - 1) {
        val prev = pts[i - 1]; val curr = pts[i]; val next = pts[i + 1]
        val d0 = (curr - prev).length(); val d1 = (next - curr).length()
        if (d0 < 0.01f || d1 < 0.01f) { out.add(curr); continue }
        val pS = prev + (curr - prev) * (1f - (cornerR / d0).coerceAtMost(0.45f))
        val pE = curr + (next - curr) * (cornerR / d1).coerceAtMost(0.45f)
        for (s in 0..7) {
            val t = s / 7f
            out.add(pS * ((1 - t) * (1 - t)) + curr * (2 * (1 - t) * t) + pE * (t * t))
        }
    }
    out.add(pts.last()); return out
}

/** Wandelt Wegpunkte in TreeSegs um; Radius verläuft von baseR (Bett) nach tipR (Spitze). */
private fun pathToTreeSegs(path: List<Vec3>, baseR: Float, tipR: Float): List<TreeSeg> {
    if (path.size < 2) return emptyList()
    val topZ = path.maxOf { it.z }.coerceAtLeast(0.1f)
    return (0 until path.size - 1).map { i ->
        val p0 = path[i]; val p1 = path[i + 1]
        val r0 = (baseR + (tipR - baseR) * (p0.z / topZ)).coerceAtLeast(tipR)
        val r1 = (baseR + (tipR - baseR) * (p1.z / topZ)).coerceAtLeast(tipR)
        TreeSeg(p0, r0, p1, r1)
    }
}

/**
 * Baut aus den gesetzten Stützpunkten organische Bäume mit Kollisionserkennung:
 * Stützen weichen Hindernissen aus und biegen sich mit sanften Radien zum Bett.
 */
private fun buildTreeSupports(tips: List<Vec3>, collider: SupportCollider?, p: SupportProfile): List<TreeSeg> {
    if (tips.isEmpty()) return emptyList()
    val tipR = p.tipDiameter / 2f
    val branchR = p.branchDiameter / 2f
    val mergeDist = (p.branchDiameter * 4f).coerceIn(8f, 25f)

    // Greedy-Clustering: nahe Spitzen teilen sich einen gemeinsamen Stamm
    val remaining = tips.toMutableList()
    val clusters = ArrayList<MutableList<Vec3>>()
    while (remaining.isNotEmpty()) {
        val seed = remaining.removeAt(remaining.size - 1)
        val cl = mutableListOf(seed)
        val it = remaining.iterator()
        while (it.hasNext()) {
            val v = it.next()
            if (cl.any { c -> sqrt((c.x - v.x) * (c.x - v.x) + (c.y - v.y) * (c.y - v.y)) < mergeDist }) {
                cl.add(v); it.remove()
            }
        }
        clusters.add(cl)
    }

    val segs = ArrayList<TreeSeg>()
    for (cl in clusters) {
        if (cl.size == 1) {
            // Einzelne Spitze: Kollisionserkennung + Routing + Glättung
            val path = routeSupportPath(cl[0], collider, p)
            segs.addAll(pathToTreeSegs(smoothSupportPath(path, 5f), branchR, tipR))
        } else {
            // Mehrere Spitzen: gemeinsamer Stamm bis zur Gabelhöhe (gerade)
            val cx = cl.map { it.x }.average().toFloat()
            val cy = cl.map { it.y }.average().toFloat()
            val forkZ = (cl.minOf { it.z } * 0.4f).coerceAtLeast(0.5f)
            val fork = Vec3(cx, cy, forkZ)
            val midR = (branchR * 0.7f).coerceAtLeast(tipR)
            segs.add(TreeSeg(Vec3(cx, cy, 0f), branchR, fork, midR))
            for (tip in cl) {
                val top = Vec3(tip.x, tip.y, (tip.z - p.xyGap).coerceAtLeast(forkZ))
                segs.add(TreeSeg(fork, midR, top, tipR))
            }
        }
    }
    return segs
}

@Composable
private fun SlicerCanvas(projection: Projection?, modelBitmap: ImageBitmap?, treeSegs: List<TreeSeg>) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        // Druckbett-Gitter (XY-Ebene, z=0).
        val grid = AccentYellow.copy(alpha = 0.16f)
        val gridStrong = AccentYellow.copy(alpha = 0.32f)
        val proj = projection
        if (proj != null) {
            val step = 20f
            var x = -BED_X / 2
            while (x <= BED_X / 2 + 0.1f) {
                val p1 = proj.worldToScreen(Vec3(x, -BED_Y / 2, 0f))
                val p2 = proj.worldToScreen(Vec3(x, BED_Y / 2, 0f))
                drawLine(if (x == 0f) gridStrong else grid, p1, p2, strokeWidth = if (x == 0f) 2f else 1f)
                x += step
            }
            var y = -BED_Y / 2
            while (y <= BED_Y / 2 + 0.1f) {
                val p1 = proj.worldToScreen(Vec3(-BED_X / 2, y, 0f))
                val p2 = proj.worldToScreen(Vec3(BED_X / 2, y, 0f))
                drawLine(if (y == 0f) gridStrong else grid, p1, p2, strokeWidth = if (y == 0f) 2f else 1f)
                y += step
            }

            // Modell-Dreiecke: im Hintergrund vorgerendertes Bitmap nur blitten
            // (Zeichnen der Pfade pro Frame auf dem UI-Thread würde bei großen
            // Meshes ANR auslösen).
            modelBitmap?.let { drawImage(it) }

            // Organische Baum-Stützen: unten dick, nach oben auf Tip-Durchmesser
            // verjüngt, hohl gezeichnet (durchscheinende Füllung + Kanten).
            val trunkColor = Color(0xFF66D9FF)
            for (seg in treeSegs) {
                val s0 = proj.worldToScreen(seg.a); val s1 = proj.worldToScreen(seg.b)
                // mm-Radius → Bildschirm-Halbbreite (Mindestbreite, damit sichtbar).
                val w0 = (seg.rA * proj.pxPerMm).coerceAtLeast(1.5f)
                val w1 = (seg.rB * proj.pxPerMm).coerceAtLeast(1f)
                val dir = s1 - s0
                val len = sqrt(dir.x * dir.x + dir.y * dir.y)
                if (len < 0.01f) continue
                val nx = -dir.y / len; val ny = dir.x / len
                val a = Offset(s0.x + nx * w0, s0.y + ny * w0)
                val b = Offset(s0.x - nx * w0, s0.y - ny * w0)
                val c = Offset(s1.x - nx * w1, s1.y - ny * w1)
                val d = Offset(s1.x + nx * w1, s1.y + ny * w1)
                val tube = Path().apply {
                    moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(c.x, c.y); lineTo(d.x, d.y); close()
                }
                drawPath(tube, trunkColor.copy(alpha = 0.16f)) // hohle Innenfläche
                drawLine(trunkColor, a, d, strokeWidth = 1.6f) // Außenkanten
                drawLine(trunkColor, b, c, strokeWidth = 1.6f)
            }
        }
    }
}

@Composable
private fun TransformPanel(
    scale: Float,
    onScale: (Float) -> Unit,
    simplifyLevel: Int,
    onSimplify: (Int) -> Unit,
    onRotate: (Vec3) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.padding(12.dp),
        color = SurfaceVariant.copy(alpha = 0.96f),
        shape = RoundedCornerShape(12.dp),
        shadowElevation = 8.dp
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Straighten, null, tint = AccentYellow, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Skalierung: ${"%.0f".format(scale * 100)} %", color = OnSurface, fontSize = 13.sp)
            }
            Slider(
                value = scale,
                onValueChange = onScale,
                valueRange = 0.1f..3f,
                colors = SliderDefaults.colors(thumbColor = AccentYellow, activeTrackColor = AccentYellow)
            )
            Text(
                "Vereinfachung: " + if (simplifyLevel == 0) "aus" else "$simplifyLevel / 10",
                color = OnSurface, fontSize = 13.sp
            )
            Slider(
                value = simplifyLevel.toFloat(),
                onValueChange = { onSimplify(it.toInt().coerceIn(0, 10)) },
                valueRange = 0f..10f,
                steps = 9,
                colors = SliderDefaults.colors(thumbColor = AccentYellow, activeTrackColor = AccentYellow)
            )
            Text("Drehen (90°-Schritte)", color = OnSurfaceDim, fontSize = 11.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RotChip("X") { onRotate(Vec3(1f, 0f, 0f)) }
                RotChip("Y") { onRotate(Vec3(0f, 1f, 0f)) }
                RotChip("Z") { onRotate(Vec3(0f, 0f, 1f)) }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onReset) {
                    Icon(Icons.Default.Refresh, null, tint = AccentYellow, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Reset", color = AccentYellow, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun RotChip(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(SurfaceDark)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = OnSurface, fontWeight = FontWeight.Bold, fontSize = 15.sp)
    }
}

// ── Profil-Verwaltung (lokaler Import; geht später an den slice-service) ──────

@Composable
private fun ProfilesDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var refresh by remember { mutableStateOf(0) }
    var pendingType by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        val type = pendingType
        if (uri != null && type != null) {
            try {
                val name = context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
                } ?: "${type}_${System.currentTimeMillis()}.json"
                val dest = File(profileDir(context, type), name)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { input.copyTo(it) }
                }
                Toast.makeText(context, "Profil importiert: $name", Toast.LENGTH_SHORT).show()
                refresh++
            } catch (e: Exception) {
                Toast.makeText(context, "Import fehlgeschlagen", Toast.LENGTH_SHORT).show()
            }
        }
        pendingType = null
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(color = SurfaceDark, shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(18.dp).fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Slicer-Profile", color = OnSurface, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Schließen", tint = OnSurfaceDim) }
                }
                Text(
                    "Orca-JSON-Profile importieren. Wichtig: Drucker- und Slice-(Process-)Profil.",
                    color = OnSurfaceDim, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp)
                )

                key(refresh) {
                    PROFILE_TYPES.forEach { type ->
                        ProfileTypeSection(
                            type = type,
                            onUpload = { pendingType = type; picker.launch("application/json") },
                            onDeleted = { refresh++ }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileTypeSection(type: String, onUpload: () -> Unit, onDeleted: () -> Unit) {
    val context = LocalContext.current
    val label = when (type) {
        "printer" -> "Drucker-Profil"
        "process" -> "Slice-Profil (Process)"
        else -> "Filament-Profil"
    }
    val files = profileDir(context, type).listFiles()?.filter { it.extension == "json" }?.sortedBy { it.name } ?: emptyList()

    Column(Modifier.padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = AccentYellow, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            TextButton(onClick = onUpload) {
                Icon(Icons.Default.UploadFile, null, tint = AccentYellow, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Hochladen", color = AccentYellow, fontSize = 12.sp)
            }
        }
        if (files.isEmpty()) {
            Text("— keine —", color = OnSurfaceDim, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        } else {
            files.forEach { f ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 1.dp)) {
                    Text(f.name, color = OnSurface, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f), maxLines = 1)
                    Icon(
                        Icons.Default.Delete, "Löschen", tint = ErrorRed,
                        modifier = Modifier.size(18.dp).clip(RoundedCornerShape(4.dp)).clickable { f.delete(); onDeleted() }
                    )
                }
            }
        }
        HorizontalDivider(color = Color(0xFF2A2A2A), modifier = Modifier.padding(top = 6.dp))
    }
}
