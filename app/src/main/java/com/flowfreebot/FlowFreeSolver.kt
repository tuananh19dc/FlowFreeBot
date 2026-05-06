package com.flowfreebot

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log

// ══════════════════════════════════════════════════════════════════════════════
//  DATA CLASSES
// ══════════════════════════════════════════════════════════════════════════════

data class Cell(val r: Int, val c: Int)
typealias SolvedPaths = Map<String, List<Cell>>

// ══════════════════════════════════════════════════════════════════════════════
//  BẢN ĐỒ MÀU (TỐI ƯU HÓA: THÊM BÓNG ĐỔ VÀ PHẢN QUANG, XÓA MÀU RÁC)
// ══════════════════════════════════════════════════════════════════════════════

object ColorPresets {
    val map: Map<String, Triple<Int, Int, Int>> = mapOf(
        "R"  to Triple(255, 56,  56),  "R_dk"  to Triple(160, 30,  30),
        "B"  to Triple(45,  152, 218), "B_dk"  to Triple(20,  80, 150),
        "Y"  to Triple(254, 202, 87),  "Y_dk"  to Triple(160, 130, 40),
        "G"  to Triple(32,  191, 107), "G_dk"  to Triple(20,  110, 50),
        "O"  to Triple(250, 130, 49),  "O_dk"  to Triple(180, 80,  20),
        "P"  to Triple(136, 84,  208), "P_lt"  to Triple(180, 120, 240), // Tím phản quang
        "M"  to Triple(235, 59,  90),  "M_dk"  to Triple(140, 30,  50),
        "C"  to Triple(15,  185, 177), "C_dk"  to Triple(10,  110, 100),
        "BN" to Triple(140, 70,  30),  "BN_lt" to Triple(210, 110, 50),  // Nâu phản quang
        "GR" to Triple(189, 195, 199), "GR_dk" to Triple(120, 125, 130),
        "WH" to Triple(245, 245, 245)
    )
}

// ══════════════════════════════════════════════════════════════════════════════
//  FLOWFREESOLVER – LÕI THUẬT TOÁN
// ══════════════════════════════════════════════════════════════════════════════

object FlowFreeSolver {

    private const val TAG = "FlowFreeSolver"

    private fun getPixelRGB(pixels: IntArray, w: Int, h: Int, x: Double, y: Double): Triple<Int, Int, Int> {
        val cx = x.toInt().coerceIn(0, w - 1)
        val cy = y.toInt().coerceIn(0, h - 1)
        val pxColor = pixels[cy * w + cx]
        return Triple((pxColor shr 16) and 0xFF, (pxColor shr 8) and 0xFF, pxColor and 0xFF)
    }

    private fun getClosestColor(r: Int, g: Int, b: Int): String {
        var minDist = Int.MAX_VALUE
        var best = "0"
        for ((key, rgb) in ColorPresets.map) {
            val d = (r - rgb.first) * (r - rgb.first) +
                    (g - rgb.second) * (g - rgb.second) +
                    (b - rgb.third) * (b - rgb.third)
            if (d < minDist) { minDist = d; best = key }
        }
        // Gộp các màu sáng/tối (vd: BN_lt, R_dk) về mã màu gốc (BN, R)
        if (best.contains("_")) best = best.split("_")[0]
        return best
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  PIPELINE: LỌC NỀN -> BẦU CỬ -> PHÂN TÍCH LUMA TƯỜNG
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
                // Focus vào đúng 50% trung tâm ô
                val innerLeft = c * cellW + cellW * 0.25
                val innerTop = r * cellH + cellH * 0.25
                val innerW = cellW * 0.5
                val innerH = cellH * 0.5

                var maxLuma = 0.0
                var minLuma = 999.0
                val votes = mutableMapOf<String, Int>()

                // MULTI-SAMPLING 25 ĐIỂM
                for (i in 0..4) {
                    for (j in 0..4) {
                        val pxX = innerLeft + (innerW * i / 4.0)
                        val pxY = innerTop + (innerH * j / 4.0)
                        val rgb = getPixelRGB(pixels, w, h, pxX, pxY)

                        val (pr, pg, pb) = rgb

                        // CỬA AN NINH: Chỉ cho phép pixel có màu sắc tươi sáng đi bầu cử.
                        // Nền game là màu xanh xám tối, nên nếu RGB đều thấp thì đó là Rác/Nền/Tường
                        val isBackground = (pr < 100 && pg < 120 && pb < 160)

                        if (!isBackground) {
                            val color = getClosestColor(pr, pg, pb)
                            votes[color] = (votes[color] ?: 0) + 1
                        }

                        // Vẫn tính Luma của toàn bộ 25 điểm để làm dữ liệu phân biệt Tường và Nền
                        val luma = 0.299 * pr + 0.587 * pg + 0.114 * pb
                        if (luma > maxLuma) maxLuma = luma
                        if (luma < minLuma) minLuma = luma
                    }
                }

                // GIAI ĐOẠN 1: TÌM MÀU BI
                // Cần ít nhất 4 tia đâm trúng màu (trên tổng 25 tia) để xác nhận đây là bi
                val dominantColor = votes.filter { it.value >= 4 }.maxByOrNull { it.value }?.key

                if (dominantColor != null) {
                    board[r][c] = dominantColor
                } else {
                    // GIAI ĐOẠN 2: PHÂN LOẠI TƯỜNG (WALL) HAY NỀN (EMPTY)
                    // Tường gạch chéo là đan xen giữa sọc xám và nền đen -> Chênh lệch Luma > 10.
                    // Nền trống là đen lỳ -> Chênh lệch Luma cực thấp.
                    board[r][c] = if (maxLuma - minLuma > 10.0) "W" else "0"
                }
            }
        }
        return board
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  LÕI DFS SOLVER (Không thay đổi)
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