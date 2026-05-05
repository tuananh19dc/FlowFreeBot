package com.flowfreebot



import android.graphics.Bitmap

import android.graphics.Rect

import android.util.Log

import kotlin.math.floor

import kotlin.math.roundToInt



// ══════════════════════════════════════════════════════════════════════════════

//  DATA CLASSES

// ══════════════════════════════════════════════════════════════════════════════



data class Cell(val r: Int, val c: Int)

data class GridSize(val rows: Int, val cols: Int)



typealias SolvedPaths = Map<String, List<Cell>>



// ══════════════════════════════════════════════════════════════════════════════

//  BẢN ĐỒ MÀU – Port từ RGB_PRESETS trong JS

// ══════════════════════════════════════════════════════════════════════════════



object ColorPresets {

    val map: Map<String, Triple<Int, Int, Int>> = mapOf(

        "R" to Triple(255, 56,  56),

        "B" to Triple(45,  152, 218),

        "Y" to Triple(254, 202, 87),

        "G" to Triple(32,  191, 107),

        "O" to Triple(250, 130, 49),

        "P" to Triple(136, 84,  208),

        "M" to Triple(235, 59,  90),

        "C" to Triple(15,  185, 177)

        // Xóa dòng của màu "L" ở đây đi nhé! (Nhớ bỏ luôn dấu phẩy ở dòng "C" nếu bị báo lỗi)

    )

    const val THRESHOLD_SQ = 140 * 140

}



// ══════════════════════════════════════════════════════════════════════════════

//  FLOWFREESOLVER – LÕI THUẬT TOÁN

// ══════════════════════════════════════════════════════════════════════════════



object FlowFreeSolver {



    private const val TAG = "FlowFreeSolver"



    // ─────────────────────────────────────────────────────────────────────────

    //  1. AUTO DETECT GRID SIZE  (port từ autoDetectGridSize JS)

    // ─────────────────────────────────────────────────────────────────────────



    fun autoDetectGridSize(bitmap: Bitmap): GridSize? {

        val w = bitmap.width

        val h = bitmap.height



        val pixels = IntArray(w * h)

        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)



        val vertHist = IntArray(w)

        val horzHist = IntArray(h)



        for (y in 0 until h) {

            for (x in 0 until w) {

                val px = pixels[y * w + x]

                val r  = (px shr 16) and 0xFF

                val g  = (px shr 8)  and 0xFF

                val b  =  px         and 0xFF

                if (r + g + b > 250) {

                    vertHist[x]++

                    horzHist[y]++

                }

            }

        }



        val vLines = findAndMergeLines(vertHist, h * 0.3)

        val hLines = findAndMergeLines(horzHist, w * 0.3)



        Log.d(TAG, "vLines=$vLines  hLines=$hLines")



        val cols = calculateSize(vLines, w)

        val rows = calculateSize(hLines, h)



        val finalCols = if (cols > 0) cols else if (vLines.size > 1) vLines.size - 1 else 0

        val finalRows = if (rows > 0) rows else if (hLines.size > 1) hLines.size - 1 else 0



        return if (finalCols >= 4 && finalRows >= 4) GridSize(finalRows, finalCols) else null

    }



    private fun findAndMergeLines(histogram: IntArray, threshold: Double): List<Int> {

        val peaks = mutableListOf<Int>()

        for (i in 1 until histogram.size - 1) {

            if (histogram[i] > threshold &&

                histogram[i] >= histogram[i - 1] &&

                histogram[i] >= histogram[i + 1]

            ) peaks.add(i)

        }

        if (peaks.isEmpty()) return emptyList()



        val merged = mutableListOf<Int>()

        var group  = mutableListOf(peaks[0])

        for (i in 1 until peaks.size) {

            if (peaks[i] - group.last() < 10) group.add(peaks[i])

            else {

                merged.add(group.average().roundToInt())

                group = mutableListOf(peaks[i])

            }

        }

        if (group.isNotEmpty()) merged.add(group.average().roundToInt())

        return merged

    }



    private fun calculateSize(lines: List<Int>, total: Int): Int {

        if (lines.size < 2) return 0

        val gaps      = (1 until lines.size).map { lines[it] - lines[it - 1] }.sorted()

        val medianGap = gaps[gaps.size / 2]

        return if (medianGap == 0) 0 else (total.toDouble() / medianGap).roundToInt()

    }



    // ─────────────────────────────────────────────────────────────────────────

    //  2. LẤY PIXEL TỪ BITMAP

    // ─────────────────────────────────────────────────────────────────────────



    // ─────────────────────────────────────────────────────────────────────────

    //  2. LẤY MÀU TRUNG BÌNH CỦA KHỐI 49 PIXEL (Chống chói sáng)

    // ─────────────────────────────────────────────────────────────────────────



    private fun getPixelRGB(pixels: IntArray, w: Int, h: Int, x: Double, y: Double): Triple<Int, Int, Int> {

        val cx = Math.max(0, Math.min(Math.floor(x).toInt(), w - 1))

        val cy = Math.max(0, Math.min(Math.floor(y).toInt(), h - 1))

        val pxColor = pixels[cy * w + cx]

        return Triple((pxColor shr 16) and 0xFF, (pxColor shr 8) and 0xFF, pxColor and 0xFF)

    }



    // ─────────────────────────────────────────────────────────────────────────

    //  3. EUCLIDEAN RGB  (port từ getClosestColor JS)

    // ─────────────────────────────────────────────────────────────────────────



    fun getClosestColor(r: Int, g: Int, b: Int): String {

        // 1. Nhận diện nền trống (màu tối)

        if (r < 110 && g < 150 && b < 180) {

            return "0"

        }



        // Tính toán độ lệch giữa màu cao nhất và thấp nhất để xem nó có rực rỡ không

        val max = maxOf(r, g, b)

        val min = minOf(r, g, b)

        val diff = max - min



        // 2. CHẶN ĐỨNG Ô GẠCH CHÉO (WALL):

        // Ô gạch chéo là màu xám (diff rất nhỏ), đủ sáng để lọt qua bước 1,

        // và không được quá sáng chói (tránh trường hợp tâm điểm của chấm màu bị chóa sáng trắng).

        if (diff < 65 && min < 200) {

            return "W" // Trả về "W" (Wall) để thuật toán né ô này ra!

        }



        // 3. Khớp màu chuẩn (cho các chấm tròn rực rỡ)

        var minDist = Int.MAX_VALUE

        var best    = "0"

        for ((key, rgb) in ColorPresets.map) {

            val (pr, pg, pb) = rgb

            val d = (r-pr)*(r-pr) + (g-pg)*(g-pg) + (b-pb)*(b-pb)

            if (d < minDist) { minDist = d; best = key }

        }

        return if (minDist < ColorPresets.THRESHOLD_SQ) best else "0"

    }



    // ─────────────────────────────────────────────────────────────────────────

    //  4. SCAN ẢNH → BOARD  (port từ scanImageForColors JS)

    // ─────────────────────────────────────────────────────────────────────────



    // ─────────────────────────────────────────────────────────────────────────

    //  4. SCAN ẢNH → BOARD (Tích hợp Cảm biến kết cấu nhận diện Tường)

    // ─────────────────────────────────────────────────────────────────────────



    fun scanBitmapToBoard(bitmap: Bitmap, numRows: Int, numCols: Int): Array<Array<String>> {

        val w = bitmap.width

        val h = bitmap.height

        val pixels = IntArray(w * h)

        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)



        val cellW = w.toDouble() / numCols

        val cellH = h.toDouble() / numRows

        val board = Array(numRows) { Array(numCols) { "0" } }



        for (r in 0 until numRows) {

            for (c in 0 until numCols) {

                val cx = c * cellW + cellW / 2

                val cy = r * cellH + cellH / 2



                // Lấy điểm trung tâm

                val centerPt = getPixelRGB(pixels, w, h, cx, cy)

                var color = getClosestColor(centerPt.first, centerPt.second, centerPt.third)



                // Nếu là nền tối, bắt đầu bật chế độ "Cảm biến kết cấu"

                if (color == "0") {

                    val ox = cellW * 0.25; val oy = cellH * 0.25

                    val pts = listOf(

                        centerPt,

                        getPixelRGB(pixels, w, h, cx - ox, cy),

                        getPixelRGB(pixels, w, h, cx + ox, cy),

                        getPixelRGB(pixels, w, h, cx, cy - oy),

                        getPixelRGB(pixels, w, h, cx, cy + oy)

                    )



                    var maxLuma = 0.0

                    var minLuma = 999.0

                    val votes = mutableMapOf<String, Int>()



                    for ((vr, vg, vb) in pts) {

                        // Tính toán độ sáng (Luminance) của từng điểm

                        val luma = 0.299 * vr + 0.587 * vg + 0.114 * vb

                        if (luma > maxLuma) maxLuma = luma

                        if (luma < minLuma) minLuma = luma



                        val vc = getClosestColor(vr, vg, vb)

                        if (vc != "0") votes[vc] = (votes[vc] ?: 0) + 1

                    }



                    // Nếu độ chênh lệch sáng tối > 15 (nghĩa là có sọc kẻ gạch chéo)

                    // VÀ không có màu rực rỡ nào lọt vào -> Đích thị là Tường (W)

                    if (maxLuma - minLuma > 15 && votes.isEmpty()) {

                        color = "W"

                    } else {

                        // Nếu không phải tường, lấy màu được biểu quyết nhiều nhất

                        color = votes.maxByOrNull { it.value }?.key ?: "0"

                    }

                }

                board[r][c] = color

            }

        }

        return board

    }



    // ─────────────────────────────────────────────────────────────────────────

    //  5. DFS SOLVER  (port từ solveDFS JS + hỗ trợ vật cản 'W')

    // ─────────────────────────────────────────────────────────────────────────



    fun solveDFS(board: Array<Array<String>>, numRows: Int, numCols: Int): SolvedPaths? {

        val visited = Array(numRows) { r ->

            Array(numCols) { c ->

                when (board[r][c]) { "W" -> "W"; "0" -> ""; else -> board[r][c] }

            }

        }



        val endpoints = mutableMapOf<String, MutableList<Cell>>()

        for (r in 0 until numRows)

            for (c in 0 until numCols) {

                val v = board[r][c]

                if (v != "0" && v != "W")

                    endpoints.getOrPut(v) { mutableListOf() }.add(Cell(r, c))

            }



        for ((key, pts) in endpoints) {

            if (pts.size != 2) {

                Log.e(TAG, "Màu $key có ${pts.size} điểm – cần đúng 2!")

                return null

            }

        }



        val colorKeys = endpoints.keys.toList()

        val paths     = mutableMapOf<String, List<Cell>>()

        val moves     = arrayOf(intArrayOf(-1,0), intArrayOf(1,0), intArrayOf(0,-1), intArrayOf(0,1))



        fun isFull(): Boolean {

            for (r in 0 until numRows)

                for (c in 0 until numCols)

                    if (visited[r][c] == "") return false

            return true

        }



        fun solveColor(idx: Int): Boolean {

            if (idx == colorKeys.size) return isFull()

            val color = colorKeys[idx]

            val start = endpoints[color]!![0]

            val end   = endpoints[color]!![1]



            fun search(r: Int, c: Int, path: MutableList<Cell>): Boolean {

                if (r == end.r && c == end.c) {

                    paths[color] = path.toList()

                    return solveColor(idx + 1)

                }

                for ((dr, dc) in moves) {

                    val nr = r + dr; val nc = c + dc

                    if (nr !in 0 until numRows || nc !in 0 until numCols) continue

                    val isEnd = nr == end.r && nc == end.c

                    if (visited[nr][nc] == "" || isEnd) {

                        val old = visited[nr][nc]

                        visited[nr][nc] = color

                        path.add(Cell(nr, nc))

                        if (search(nr, nc, path)) return true

                        path.removeAt(path.lastIndex)

                        visited[nr][nc] = old

                    }

                }

                return false

            }

            return search(start.r, start.c, mutableListOf(start))

        }



        return if (solveColor(0)) paths else null

    }



    // ─────────────────────────────────────────────────────────────────────────

    //  6. CELL → TOẠ ĐỘ PIXEL MÀN HÌNH

    // ─────────────────────────────────────────────────────────────────────────



    fun cellToScreenCoord(

        cell: Cell,

        numRows: Int, numCols: Int,

        screenWidth: Int, screenHeight: Int,

        gridRect: Rect? = null

    ): Pair<Int, Int> {

        val left   = gridRect?.left   ?: 0

        val top    = gridRect?.top    ?: 0

        val right  = gridRect?.right  ?: screenWidth

        val bottom = gridRect?.bottom ?: screenHeight



        val cellW = (right  - left).toFloat() / numCols

        val cellH = (bottom - top ).toFloat() / numRows



        val x = (left + cell.c * cellW + cellW / 2).toInt()

        val y = (top  + cell.r * cellH + cellH / 2).toInt()

        return Pair(x, y)

    }

}