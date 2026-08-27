import os

file_path = r"c:\Users\tharik\OneDrive\Documents\tethr\app\src\main\java\com\example\tethr\OverlayManager.kt"

with open(file_path, "r", encoding="utf-8") as f:
    content = f.read()

target = """                init {
                    setOnTouchListener { _, event ->
                        when (event.action) {
                            MotionEvent.ACTION_DOWN -> {
                                currentQuote = quotes.random()
                                isHolding = true
                                mainHandler.post(tickRunnable)
                                true
                            }
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                isHolding = false
                                mainHandler.removeCallbacks(tickRunnable)
                                progress = 0f
                                invalidate()
                                true
                            }
                            else -> false
                        }
                    }
                }

                override fun onDraw(canvas: Canvas) {
                    val cx = width / 2f
                    val cy = height / 2f
                    val radius = minOf(cx, cy) * 0.55f

                    // Background card
                    canvas.drawRoundRect(cx - radius * 1.4f, cy - radius * 1.7f,
                        cx + radius * 1.4f, cy + radius * 1.5f, 32f, 32f, bgPaint)

                    // Title
                    canvas.drawText("Hold to Continue", cx, cy - radius * 1.4f, titlePaint)

                    // Quote with pulsing animation
                    if (isHolding) {
                        val pulse = (155 + 100 * Math.sin(System.currentTimeMillis() / 250.0)).toInt().coerceIn(0, 255)
                        quotePaint.alpha = pulse
                        
                        // Add a slight upward floating effect in the first second
                        val floatOffset = if (progress < 0.1f) {
                            (1f - (progress * 10f)) * 15f
                        } else 0f
                        
                        canvas.drawText(currentQuote, cx, cy - radius * 1.15f + floatOffset, quotePaint)
                    }

                    // Arc track
                    val arcRect = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
                    canvas.drawArc(arcRect, -90f, 360f, false, trackPaint)

                    // Progress arc
                    if (progress > 0f) {
                        arcPaint.color = if (progress > 0.8f)
                            Color.parseColor("#4CAF50") else Color.parseColor("#FFFFFF")
                        canvas.drawArc(arcRect, -90f, 360f * progress, false, arcPaint)
                    }

                    // Center button
                    btnPaint.color = if (isHolding) Color.parseColor("#333333")
                    else Color.parseColor("#222222")
                    canvas.drawCircle(cx, cy, radius * 0.72f, btnPaint)

                    // Button text
                    val secsLeft = ((1f - progress) * 10f).coerceAtLeast(0f)
                    canvas.drawText(if (isHolding) "%.1fs".format(secsLeft) else "Hold", cx, cy + 12f, textPaint)

                    // Sub hint
                    canvas.drawText("Release = reset", cx, cy + radius * 1.3f, subPaint)
                }"""

replacement = """                init {
                    setOnTouchListener { _, event ->
                        when (event.action) {
                            MotionEvent.ACTION_DOWN -> {
                                currentQuote = quotes.random()
                                isHolding = true
                                mainHandler.post(tickRunnable)
                                true
                            }
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                isHolding = false
                                mainHandler.removeCallbacks(tickRunnable)
                                progress = 0f
                                invalidate()
                                true
                            }
                            else -> false
                        }
                    }
                }

                override fun onDraw(canvas: Canvas) {
                    val cx = width / 2f
                    val cy = height / 2f
                    val radius = minOf(cx, cy) * 0.55f

                    // Background card
                    canvas.drawRoundRect(cx - radius * 1.4f, cy - radius * 1.7f,
                        cx + radius * 1.4f, cy + radius * 1.5f, 32f, 32f, bgPaint)

                    // Title
                    canvas.drawText("Breathe to Continue", cx, cy - radius * 1.4f, titlePaint)

                    // Quote with pulsing animation
                    if (isHolding) {
                        val pulse = (155 + 100 * Math.sin(System.currentTimeMillis() / 250.0)).toInt().coerceIn(0, 255)
                        quotePaint.alpha = pulse
                        
                        val floatOffset = if (progress < 0.1f) {
                            (1f - (progress * 10f)) * 15f
                        } else 0f
                        
                        canvas.drawText(currentQuote, cx, cy - radius * 1.15f + floatOffset, quotePaint)
                    }

                    // Arc track
                    val arcRect = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
                    canvas.drawArc(arcRect, -90f, 360f, false, trackPaint)

                    // Progress arc
                    if (progress > 0f) {
                        arcPaint.color = if (progress > 0.8f)
                            Color.parseColor("#4CAF50") else Color.parseColor("#FFFFFF")
                        canvas.drawArc(arcRect, -90f, 360f * progress, false, arcPaint)
                    }

                    // Breathing Exercise Logic
                    val phaseText = when {
                        !isHolding -> "Hold to Breathe"
                        progress < 0.4f -> "Inhale..."
                        progress < 0.6f -> "Hold..."
                        else -> "Exhale..."
                    }
                    
                    val breatheScale = when {
                        !isHolding -> 0.4f
                        progress < 0.4f -> 0.4f + (0.6f * (progress / 0.4f)) // 0.4 to 1.0
                        progress < 0.6f -> 1.0f // hold at 1.0
                        else -> 1.0f - (0.6f * ((progress - 0.6f) / 0.4f)) // 1.0 to 0.4
                    }

                    // Center breathing circle
                    val currentRadius = radius * 0.95f * breatheScale
                    btnPaint.color = if (isHolding) Color.parseColor("#333333") else Color.parseColor("#222222")
                    canvas.drawCircle(cx, cy, currentRadius, btnPaint)

                    // Button text
                    val secsLeft = ((1f - progress) * 10f).coerceAtLeast(0f)
                    
                    // Slightly adjust text size if it's very small
                    textPaint.textSize = 32f
                    canvas.drawText(phaseText, cx, cy - 10f, textPaint)
                    
                    if (isHolding) {
                        subPaint.textSize = 28f
                        canvas.drawText(String.format("%.1fs", secsLeft), cx, cy + 30f, subPaint)
                        subPaint.textSize = 20f
                    } else {
                        canvas.drawText("10 Seconds", cx, cy + 30f, subPaint)
                    }

                    // Sub hint
                    canvas.drawText("Release = reset", cx, cy + radius * 1.3f, subPaint)
                }"""

new_content = content.replace(target, replacement)
if new_content != content:
    with open(file_path, "w", encoding="utf-8") as f:
        f.write(new_content)
    print("Replaced successfully")
else:
    print("Target string not found")
