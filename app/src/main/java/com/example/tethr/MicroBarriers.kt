package com.example.tethr

import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MathBarrierUI(onUnlock: () -> Unit) {
    val context = LocalContext.current
    var num1 by remember { mutableStateOf((10..99).random()) }
    var num2 by remember { mutableStateOf((10..99).random()) }
    var answer by remember { mutableStateOf("") }
    
    val shakeOffset = remember { Animatable(0f) }
    val coroutineScope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xE6000000)), // Translucent black
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .width(320.dp)
                .background(Color(0xFF1C1C1E), androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Quick Math", color = Color.LightGray, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 16.dp))
            Text("$num1 + $num2 = ?", color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 24.dp))

            OutlinedTextField(
                value = answer,
                onValueChange = { answer = it },
                placeholder = { Text("Type your answer", color = Color.Gray) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                    focusedContainerColor = Color(0xFF2C2C2E),
                    unfocusedContainerColor = Color(0xFF2C2C2E),
                ),
                singleLine = true,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
                    .graphicsLayer { translationX = shakeOffset.value }
            )

            Button(
                onClick = {
                    val sum = num1 + num2
                    if (answer.trim() == sum.toString()) {
                        Toast.makeText(context, "Unlocked", Toast.LENGTH_SHORT).show()
                        onUnlock()
                    } else {
                        coroutineScope.launch {
                            shakeOffset.animateTo(20f, tween(50))
                            shakeOffset.animateTo(-20f, tween(50))
                            shakeOffset.animateTo(20f, tween(50))
                            shakeOffset.animateTo(-20f, tween(50))
                            shakeOffset.animateTo(0f, tween(50))
                        }
                        num1 = (10..99).random()
                        num2 = (10..99).random()
                        answer = ""
                        
                        val vibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                            vibratorManager.defaultVibrator
                        } else {
                            @Suppress("DEPRECATION")
                            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                        }
                        if (vibrator.hasVibrator()) {
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                vibrator.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE))
                            } else {
                                @Suppress("DEPRECATION")
                                vibrator.vibrate(200)
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White)
            ) {
                Text("Submit", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.Black)
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            Text("Solve to continue scrolling", color = Color.Gray, fontSize = 12.sp)
        }
    }
}

enum class CellState { EMPTY, X, O }

@Composable
fun XoBarrierUI(onUnlock: () -> Unit) {
    val context = LocalContext.current
    var board by remember { mutableStateOf(Array(9) { CellState.EMPTY }) }
    var isUserTurn by remember { mutableStateOf(true) }
    
    fun checkWinner(b: Array<CellState>): CellState {
        val lines = listOf(
            listOf(0, 1, 2), listOf(3, 4, 5), listOf(6, 7, 8),
            listOf(0, 3, 6), listOf(1, 4, 7), listOf(2, 5, 8),
            listOf(0, 4, 8), listOf(2, 4, 6)
        )
        for (line in lines) {
            if (b[line[0]] != CellState.EMPTY && b[line[0]] == b[line[1]] && b[line[1]] == b[line[2]]) {
                return b[line[0]]
            }
        }
        return CellState.EMPTY
    }

    fun isBoardFull(b: Array<CellState>): Boolean = !b.contains(CellState.EMPTY)

    fun resetBoard() {
        board = Array(9) { CellState.EMPTY }
        isUserTurn = true
    }

    fun getAiMove(b: Array<CellState>): Int {
        val lines = listOf(
            listOf(0, 1, 2), listOf(3, 4, 5), listOf(6, 7, 8),
            listOf(0, 3, 6), listOf(1, 4, 7), listOf(2, 5, 8),
            listOf(0, 4, 8), listOf(2, 4, 6)
        )
        for (line in lines) {
            val states = line.map { b[it] }
            if (states.count { it == CellState.O } == 2 && states.count { it == CellState.EMPTY } == 1) {
                return line[states.indexOf(CellState.EMPTY)]
            }
        }
        for (line in lines) {
            val states = line.map { b[it] }
            if (states.count { it == CellState.X } == 2 && states.count { it == CellState.EMPTY } == 1) {
                return line[states.indexOf(CellState.EMPTY)]
            }
        }
        if (b[4] == CellState.EMPTY) return 4
        val emptyIndices = b.indices.filter { b[it] == CellState.EMPTY }
        return if (emptyIndices.isNotEmpty()) emptyIndices.random() else -1
    }

    LaunchedEffect(isUserTurn) {
        if (!isUserTurn) {
            delay(500)
            val aiMove = getAiMove(board)
            if (aiMove != -1) {
                val newBoard = board.clone()
                newBoard[aiMove] = CellState.O
                board = newBoard
                
                val winner = checkWinner(board)
                if (winner == CellState.O) {
                    Toast.makeText(context, "Mindless try failed. Try again.", Toast.LENGTH_SHORT).show()
                    delay(1000)
                    resetBoard()
                } else if (isBoardFull(board)) {
                    Toast.makeText(context, "Draw - Unlocked", Toast.LENGTH_SHORT).show()
                    onUnlock()
                } else {
                    isUserTurn = true
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xE6000000)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .width(320.dp)
                .background(Color(0xFF1C1C1E), androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Tic-Tac-Toe", color = Color.LightGray, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 24.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .background(Color(0xFF333333)), // border color
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                for (i in 0..2) {
                    Row(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        for (j in 0..2) {
                            val index = i * 3 + j
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .background(Color(0xFF1C1C1E))
                                    .clickable {
                                        if (isUserTurn && board[index] == CellState.EMPTY) {
                                            val newBoard = board.clone()
                                            newBoard[index] = CellState.X
                                            board = newBoard
                                            
                                            val winner = checkWinner(board)
                                            if (winner == CellState.X) {
                                                Toast.makeText(context, "You Won! Unlocked", Toast.LENGTH_SHORT).show()
                                                onUnlock()
                                            } else if (isBoardFull(board)) {
                                                Toast.makeText(context, "Draw - Unlocked", Toast.LENGTH_SHORT).show()
                                                onUnlock()
                                            } else {
                                                isUserTurn = false
                                            }
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                when (board[index]) {
                                    CellState.X -> {
                                        androidx.compose.foundation.Canvas(modifier = Modifier.size(56.dp)) {
                                            drawLine(
                                                color = Color.White,
                                                start = androidx.compose.ui.geometry.Offset(0f, 0f),
                                                end = androidx.compose.ui.geometry.Offset(size.width, size.height),
                                                strokeWidth = 10f,
                                                cap = androidx.compose.ui.graphics.StrokeCap.Round
                                            )
                                            drawLine(
                                                color = Color.White,
                                                start = androidx.compose.ui.geometry.Offset(size.width, 0f),
                                                end = androidx.compose.ui.geometry.Offset(0f, size.height),
                                                strokeWidth = 10f,
                                                cap = androidx.compose.ui.graphics.StrokeCap.Round
                                            )
                                        }
                                    }
                                    CellState.O -> {
                                        androidx.compose.foundation.Canvas(modifier = Modifier.size(56.dp)) {
                                            drawCircle(
                                                color = Color(0xFF666666),
                                                style = androidx.compose.ui.graphics.drawscope.Stroke(
                                                    width = 10f,
                                                    cap = androidx.compose.ui.graphics.StrokeCap.Round
                                                )
                                            )
                                        }
                                    }
                                    CellState.EMPTY -> {}
                                }
                            }
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = if (!isUserTurn) "Bot is thinking..." else "Win or Draw to Unlock",
                color = Color.Gray,
                fontSize = 12.sp
            )
        }
    }
}



@Composable
fun BreathingBarrierUI(onUnlock: () -> Unit) {
    var cycle by remember { mutableStateOf(1) }
    var phase by remember { mutableStateOf("Ready") }
    
    val targetScale = remember { Animatable(1.0f) }

    LaunchedEffect(Unit) {
        delay(1000)
        for (i in 1..1) {
            cycle = i
            
            // Inhale (4s)
            phase = "Inhale..."
            targetScale.animateTo(2.5f, tween(4000, easing = LinearEasing))
            
            // Hold (4s)
            phase = "Hold... (4s)"
            var remaining = 4
            while(remaining > 0) {
                phase = "Hold... (${remaining}s)"
                delay(1000)
                remaining--
            }
            
            // Exhale (4s)
            phase = "Exhale..."
            targetScale.animateTo(1.0f, tween(4000, easing = LinearEasing))
        }
        
        onUnlock()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xE6000000)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .width(320.dp)
                .background(Color(0xFF1C1C1E), androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Mindful Breathing", color = Color.LightGray, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 24.dp))
            
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(200.dp)) {
                androidx.compose.foundation.Canvas(modifier = Modifier.size(200.dp)) {
                    drawCircle(
                        color = Color(0xFF333333),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 8.dp.toPx())
                    )
                }

                Box(
                    modifier = Modifier
                        .size(100.dp)
                        .scale(targetScale.value)
                        .background(Color.White.copy(alpha = 0.2f), CircleShape)
                )
                
                Text(phase, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}
