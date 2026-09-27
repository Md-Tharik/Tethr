package com.example.tethr.data

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.functions.Functions

object Supabase {
    val client: SupabaseClient = createSupabaseClient(
        supabaseUrl = "https://mtuyrrlgwpezbolqsplw.supabase.co",
        supabaseKey = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Im10dXlycmxnd3BlemJvbHFzcGx3Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3OTA0ODA4MDQsImV4cCI6MjEwNjA1NjgwNH0.iS75MRzCKGHq_XZPBpOiAKZy_tU8_sqTLTwm7GE1klE"
    ) {
        install(Auth)
        install(Postgrest)
        install(Functions)
    }
}
