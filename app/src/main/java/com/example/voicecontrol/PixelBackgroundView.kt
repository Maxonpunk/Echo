package com.example.voicecontrol

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.random.Random

class PixelBackgroundView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint()
    private val glowPaint = Paint()
    private val cellSize = 40f
    private var cols = 0
    private var rows = 0
    private var grid: Array<BooleanArray> = emptyArray()

    var isAnimationEnabled = true
    private var showAssWord = false

    fun clearRestrictedZones() {
        restrictedZones.clear()
        invalidate()
    }

    private val letterZhe = arrayOf(
        intArrayOf(1,0,1,0,1),
        intArrayOf(0,1,1,1,0),
        intArrayOf(0,0,1,0,0),
        intArrayOf(0,1,1,1,0),
        intArrayOf(1,0,1,0,1)
    )
    private val letterO = arrayOf(
        intArrayOf(0,1,1,1,0),
        intArrayOf(1,0,0,0,1),
        intArrayOf(1,0,0,0,1),
        intArrayOf(1,0,0,0,1),
        intArrayOf(0,1,1,1,0)
    )
    private val letterP = arrayOf(
        intArrayOf(1,1,1,1,1),
        intArrayOf(1,0,0,0,1),
        intArrayOf(1,0,0,0,1),
        intArrayOf(1,0,0,0,1),
        intArrayOf(1,0,0,0,1)
    )
    private val letterA = arrayOf(
        intArrayOf(0,1,1,1,0),
        intArrayOf(1,0,0,0,1),
        intArrayOf(1,1,1,1,1),
        intArrayOf(1,0,0,0,1),
        intArrayOf(1,0,0,0,1)
    )

    private val restrictedZones = mutableListOf<RectF>()

    init {
        glowPaint.color = Color.WHITE
        glowPaint.maskFilter = BlurMaskFilter(20f, BlurMaskFilter.Blur.NORMAL)
        glowPaint.alpha = 255
    }

    private val animator = object : Runnable {
        override fun run() {
            updateGrid()
            invalidate()
            postDelayed(this, 50)
        }
    }

    fun addRestrictedZone(rect: RectF) {
        restrictedZones.add(rect)
    }

    fun showWord() {
        showAssWord = true
        invalidate()
        postDelayed({
            showAssWord = false
            invalidate()
        }, 3000)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cols = (w / cellSize).toInt() + 1
        rows = (h / cellSize).toInt() + 1
        grid = Array(cols) { BooleanArray(rows) { false } }
    }

    private fun isRestricted(x: Float, y: Float): Boolean {
        for (zone in restrictedZones) {
            if (zone.contains(x, y)) return true
        }
        return false
    }

    private fun updateGrid() {
        if (!isAnimationEnabled) {
            for (x in 0 until cols) {
                for (y in 0 until rows) {
                    grid[x][y] = false
                }
            }
            return
        }

        for (x in 0 until cols) {
            for (y in 0 until rows) {
                if (grid[x][y] && Random.nextFloat() > 0.85f) {
                    grid[x][y] = false
                }
            }
        }

        val numToLight = (cols * rows * 0.02f).toInt().coerceAtLeast(1)
        repeat(numToLight) {
            val rx = Random.nextInt(cols)
            val ry = Random.nextInt(rows)
            val centerX = (rx * cellSize) + (cellSize / 2)
            val centerY = (ry * cellSize) + (cellSize / 2)

            if (!isRestricted(centerX, centerY)) {
                grid[rx][ry] = true
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.BLACK)

        for (x in 0 until cols) {
            for (y in 0 until rows) {
                val left = x * cellSize
                val top = y * cellSize
                val right = (x + 1) * cellSize - 4
                val bottom = (y + 1) * cellSize - 4

                if (grid[x][y]) {
                    canvas.drawRect(left - 8, top - 8, right + 8, bottom + 8, glowPaint)
                    paint.color = Color.WHITE
                    canvas.drawRect(left + 4, top + 4, right - 4, bottom - 4, paint)
                } else {
                    paint.color = Color.parseColor("#111111")
                    canvas.drawRect(left, top, right, bottom, paint)
                }
            }
        }

        if (showAssWord) {
            drawWord(canvas)
        }

        // 3. Виньетка (градиент)
        val gradientPaint = Paint()
        val shader = LinearGradient(
            0f, 0f, 0f, height.toFloat(),
            intArrayOf(Color.BLACK, Color.TRANSPARENT, Color.TRANSPARENT, Color.BLACK),
            floatArrayOf(0f, 0.15f, 0.85f, 1f), Shader.TileMode.CLAMP
        )
        gradientPaint.shader = shader
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), gradientPaint)
    }

    private fun drawWord(canvas: Canvas) {
        val word = arrayOf(letterZhe, letterO, letterP, letterA)
        val letterWidth = 5
        val spacing = 1
        val totalCols = (letterWidth * 4) + (spacing * 3) // 4 буквы + 3 пробела

        val wordWidthPx = totalCols * cellSize
        val startX = (width - wordWidthPx) / 2
        val startY = (height / 3f) - (5 * cellSize / 2)

        paint.color = Color.WHITE
        paint.style = Paint.Style.FILL

        var currentX = startX
        for (i in word.indices) {
            val letter = word[i]
            for (row in 0 until 5) {
                for (col in 0 until 5) {
                    if (letter[row][col] == 1) {
                        val left = currentX + col * cellSize
                        val top = startY + row * cellSize
                        canvas.drawRect(left, top, left + cellSize - 4, top + cellSize - 4, paint)
                    }
                }
            }
            currentX += (letterWidth + spacing) * cellSize
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post(animator)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(animator)
    }
}