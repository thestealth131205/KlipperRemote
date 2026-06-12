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
import androidx.compose.ui.graphics.Path
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

private class ProjTri(val p0: Offset, val p1: Offset, val p2: Offset, val depth: Float, val color: Color)
private class Projection(
    val tris: List<ProjTri>,
    val picks: List<Pair<Offset, Vec3>>, // Bildschirm-Centroid -> Welt-Centroid
    val worldToScreen: (Vec3) -> Offset
)

private const val BED_X = 220f
private const val BED_Y = 220f

private fun computeProjection(
    model: StlModel,
    modelRot: FloatArray,
    scale: Float,
    azDeg: Float,
    elDeg: Float,
    size: IntSize
): Projection? {
    if (size.width == 0 || size.height == 0 || model.tris.isEmpty()) return null

    // 1) Modell rotieren+skalieren (um Modellmittelpunkt), dann auf Bett absenken & zentrieren.
    val transformed = ArrayList<Triple<Vec3, Vec3, Vec3>>(model.tris.size)
    val normals = ArrayList<Vec3>(model.tris.size)
    var mnz = Float.MAX_VALUE
    var sumX = 0f; var sumY = 0f; var cnt = 0
    fun tf(v: Vec3) = matVec(modelRot, (v - model.center)) * scale
    for (t in model.tris) {
        val a = tf(t.a); val b = tf(t.b); val c = tf(t.c)
        transformed.add(Triple(a, b, c))
        normals.add(matVec(modelRot, t.n).normalized())
        mnz = min(mnz, min(a.z, min(b.z, c.z)))
        sumX += a.x + b.x + c.x; sumY += a.y + b.y + c.y; cnt += 3
    }
    val shiftX = if (cnt > 0) sumX / cnt else 0f
    val shiftY = if (cnt > 0) sumY / cnt else 0f
    val drop = Vec3(-shiftX, -shiftY, -mnz)

    // 2) Kamera-Orbit (Azimut um Z, dann Elevation um X).
    val az = Math.toRadians(azDeg.toDouble()).toFloat()
    val el = Math.toRadians(elDeg.toDouble()).toFloat()
    val cam = matMul(rotAxis(Vec3(1f, 0f, 0f), el), rotAxis(Vec3(0f, 0f, 1f), az))

    // 3) Zoom so wählen, dass das Druckbett komfortabel ins Viewport passt.
    val bedMax = max(BED_X, BED_Y)
    val zoom = (min(size.width, size.height) * 0.78f) / bedMax
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
    val projTris = ArrayList<ProjTri>(transformed.size)
    val picks = ArrayList<Pair<Offset, Vec3>>(transformed.size)
    for (i in transformed.indices) {
        val (a0, b0, c0) = transformed[i]
        val a = a0 + drop; val b = b0 + drop; val c = c0 + drop
        val (pa, da) = project(a); val (pb, db) = project(b); val (pc, dc) = project(c)
        val depth = (da + db + dc) / 3f
        val shade = (0.25f + 0.75f * max(0f, normals[i].dot(light))).coerceIn(0f, 1f)
        val col = Color(
            red = 0.12f + 0.75f * shade,
            green = 0.55f + 0.40f * shade,
            blue = 0.05f + 0.20f * shade
        )
        projTris.add(ProjTri(pa, pb, pc, depth, col))
        val centroidWorld = (a + b + c) * (1f / 3f)
        val centroidScreen = Offset((pa.x + pb.x + pc.x) / 3f, (pa.y + pb.y + pc.y) / 3f)
        picks.add(centroidScreen to centroidWorld)
    }
    // Painter's algorithm: hinten zuerst (großes depth zuerst).
    projTris.sortByDescending { it.depth }
    return Projection(projTris, picks, worldToScreen)
}

// ── Screen ───────────────────────────────────────────────────────────────────

private enum class SlicerTool { NONE, SUPPORT, TRANSFORM }
private val PROFILE_TYPES = listOf("printer", "process", "filament")
private fun profileDir(ctx: android.content.Context, type: String) =
    File(ctx.filesDir, "slicer_profiles/$type").apply { mkdirs() }

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
    var az by remember { mutableStateOf(35f) }
    var el by remember { mutableStateOf(60f) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val supports = remember { mutableStateListOf<Vec3>() }

    var showProfiles by remember { mutableStateOf(false) }

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
            val parsed = withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }?.let { parseModel(it, name) }
                } catch (e: Exception) { null }
            }
            loading = false
            if (parsed == null) {
                Toast.makeText(context, "Modell konnte nicht gelesen werden (STL/OBJ/3MF)", Toast.LENGTH_SHORT).show()
            } else {
                model = parsed
                modelName = name ?: "modell"
                modelRot = matIdentity(); scaleVal = 1f; supports.clear()
            }
        }
    }

    val projection by remember(model, modelRot, scaleVal, az, el, viewport) {
        derivedStateOf { model?.let { computeProjection(it, modelRot, scaleVal, az, el, viewport) } }
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
                .pointerInput(tool, model) {
                    detectDragGestures { _, drag ->
                        // Orbit nur, wenn nicht im Stützen-Modus.
                        if (tool != SlicerTool.SUPPORT) {
                            az += drag.x * 0.4f
                            el = (el + drag.y * 0.4f).coerceIn(5f, 89f)
                        }
                    }
                }
                .pointerInput(tool, projection) {
                    detectTapGestures { offset ->
                        if (tool == SlicerTool.SUPPORT) {
                            val proj = projection ?: return@detectTapGestures
                            // Nächstgelegenen projizierten Dreiecks-Mittelpunkt picken.
                            var best: Vec3? = null; var bestD = Float.MAX_VALUE
                            for ((screen, world) in proj.picks) {
                                val dx = screen.x - offset.x; val dy = screen.y - offset.y
                                val d = dx * dx + dy * dy
                                if (d < bestD) { bestD = d; best = world }
                            }
                            if (best != null && bestD < 60f * 60f) supports.add(best)
                        }
                    }
                }
        ) {
            SlicerCanvas(projection = projection, supports = supports)

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

            // Stützen-Hinweis
            if (tool == SlicerTool.SUPPORT && model != null) {
                Text(
                    "Tippe auf das Modell, um Stützpunkte zu setzen (${supports.size})",
                    color = Color.Black,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(8.dp)
                        .background(AccentYellow, RoundedCornerShape(6.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }

            // Transform-Panel
            if (tool == SlicerTool.TRANSFORM && model != null) {
                TransformPanel(
                    scale = scaleVal,
                    onScale = { scaleVal = it; supports.clear() },
                    onRotate = { axis ->
                        val r = rotAxis(axis, (Math.PI / 2).toFloat())
                        modelRot = matMul(r, modelRot); supports.clear()
                    },
                    onReset = { modelRot = matIdentity(); scaleVal = 1f; supports.clear() },
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
                        "${it.tris.size} Flächen · ${"%.0f×%.0f×%.0f".format(s.x * scaleVal, s.y * scaleVal, s.z * scaleVal)} mm"
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

@Composable
private fun SlicerCanvas(projection: Projection?, supports: List<Vec3>) {
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

            // Modell-Dreiecke (Painter's-Sortierung bereits angewandt).
            for (t in proj.tris) {
                val path = Path().apply {
                    moveTo(t.p0.x, t.p0.y); lineTo(t.p1.x, t.p1.y); lineTo(t.p2.x, t.p2.y); close()
                }
                drawPath(path, t.color)
            }

            // Stützen als kleine organische Bäume zeichnen.
            for (sp in supports) {
                val top = proj.worldToScreen(sp)
                val base = proj.worldToScreen(Vec3(sp.x, sp.y, 0f))
                drawLine(Color(0xFF66D9FF), base, top, strokeWidth = 3f)
                // zwei kleine Verzweigungen oben
                drawLine(Color(0xFF66D9FF), top, Offset(top.x - 9f, top.y - 7f), strokeWidth = 2f)
                drawLine(Color(0xFF66D9FF), top, Offset(top.x + 9f, top.y - 7f), strokeWidth = 2f)
            }
        }
    }
}

@Composable
private fun TransformPanel(
    scale: Float,
    onScale: (Float) -> Unit,
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
