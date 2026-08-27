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
import com.google.mlkit.nl.languageid.LanguageIdentification
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xF2000000)) // Translucent black
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Cognitive Check", color = Color.White, fontSize = 24.sp, modifier = Modifier.padding(bottom = 32.dp))
        Text("$num1 + $num2 = ?", color = Color.White, fontSize = 48.sp, modifier = Modifier.padding(bottom = 32.dp))

        OutlinedTextField(
            value = answer,
            onValueChange = { answer = it },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = Color.White,
                unfocusedBorderColor = Color.Gray
            ),
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth(0.6f)
                .padding(bottom = 32.dp)
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
            modifier = Modifier.fillMaxWidth(0.6f),
            colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
        ) {
            Text("Unlock", fontSize = 18.sp, color = Color.White)
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xF2000000))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Tic-Tac-Toe Challenge", color = Color.White, fontSize = 24.sp, modifier = Modifier.padding(bottom = 16.dp))
        Text("Win or Draw to Unlock", color = Color.Gray, fontSize = 16.sp, modifier = Modifier.padding(bottom = 32.dp))

        Column(
            modifier = Modifier.width(300.dp).height(300.dp),
            verticalArrangement = Arrangement.SpaceEvenly
        ) {
            for (i in 0..2) {
                Row(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    for (j in 0..2) {
                        val index = i * 3 + j
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .padding(4.dp)
                                .background(Color.DarkGray)
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
                            Text(
                                text = when (board[index]) {
                                    CellState.X -> "X"
                                    CellState.O -> "O"
                                    CellState.EMPTY -> ""
                                },
                                color = if (board[index] == CellState.X) Color.Cyan else Color.Red,
                                fontSize = 48.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IntentionBarrierUI(onUnlock: () -> Unit) {
    val context = LocalContext.current
    var intention by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xF2000000))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Mindful Intention", color = Color.White, fontSize = 24.sp, modifier = Modifier.padding(bottom = 16.dp))
        Text("Why do you need to open this app right now?", color = Color.White, fontSize = 18.sp, modifier = Modifier.padding(bottom = 32.dp))

        OutlinedTextField(
            value = intention,
            onValueChange = { intention = it },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = Color.White,
                unfocusedBorderColor = Color.Gray
            ),
            singleLine = false,
            modifier = Modifier.fillMaxWidth().height(150.dp).padding(bottom = 32.dp)
        )

        Button(
            onClick = {
                if (intention.trim().length < 5) {
                    Toast.makeText(context, "Please be more specific.", Toast.LENGTH_SHORT).show()
                    return@Button
                }

                isLoading = true
                val languageIdentifier = LanguageIdentification.getClient()
                languageIdentifier.identifyLanguage(intention.trim())
                    .addOnSuccessListener { languageCode ->
                        isLoading = false
                        if (languageCode == "und") {
                            Toast.makeText(context, "Please state a deliberate, real intent.", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Intent accepted.", Toast.LENGTH_SHORT).show()
                            onUnlock()
                        }
                    }
                    .addOnFailureListener {
                        isLoading = false
                        Toast.makeText(context, "Intent accepted.", Toast.LENGTH_SHORT).show()
                        onUnlock()
                    }
            },
            modifier = Modifier.fillMaxWidth(0.6f),
            enabled = !isLoading,
            colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
        ) {
            if (isLoading) {
                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
            } else {
                Text("Submit", fontSize = 18.sp, color = Color.White)
            }
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
        for (i in 1..2) { // 2 cycles to save time in total, or adjust as needed
            cycle = i
            
            // Inhale (4s)
            phase = "Inhale..."
            targetScale.animateTo(2.5f, tween(4000, easing = LinearEasing))
            
            // Hold (10s)
            phase = "Hold... (10s)"
            var remaining = 10
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xF2000000))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly
    ) {
        Text("Mindful Breathing", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(300.dp)) {
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .scale(targetScale.value)
                    .background(Color.Cyan.copy(alpha = 0.5f), CircleShape)
            )
            
            Text(phase, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }

        Text("Cycle $cycle / 2", color = Color.Gray, fontSize = 18.sp)
    }
}
