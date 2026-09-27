import { serve } from "https://deno.land/std@0.168.0/http/server.ts"
import { createClient } from "https://esm.sh/@supabase/supabase-js@2.39.3"
import { google } from "https://esm.sh/googleapis@134.0.0"

serve(async (req) => {
  try {
    const supabaseClient = createClient(
      Deno.env.get("SUPABASE_URL") ?? "",
      Deno.env.get("SUPABASE_ANON_KEY") ?? "",
      {
        global: { headers: { Authorization: req.headers.get("Authorization")! } }
      }
    )

    // Authenticate the user calling the function
    const { data: { user } } = await supabaseClient.auth.getUser()
    if (!user) {
      return new Response(JSON.stringify({ error: "Unauthorized" }), { status: 401 })
    }

    const body = await req.json()
    const { purchaseToken, subscriptionId, packageName } = body

    if (!purchaseToken || !subscriptionId) {
      return new Response(JSON.stringify({ error: "Missing purchase data" }), { status: 400 })
    }

    // Connect to Google Play API
    // You MUST set the GOOGLE_PLAY_CREDENTIALS secret in Supabase 
    // containing the JSON of your Google Cloud Service Account
    const credentialsStr = Deno.env.get("GOOGLE_PLAY_CREDENTIALS")
    if (!credentialsStr) {
       console.error("Missing GOOGLE_PLAY_CREDENTIALS secret");
       return new Response(JSON.stringify({ error: "Server misconfiguration" }), { status: 500 })
    }

    const credentials = JSON.parse(credentialsStr)
    const auth = new google.auth.GoogleAuth({
      credentials,
      scopes: ["https://www.googleapis.com/auth/androidpublisher"]
    });
    
    const playDeveloperApi = google.androidpublisher({ version: "v3", auth });

    // Verify purchase
    const response = await playDeveloperApi.purchases.subscriptions.get({
      packageName: packageName || "ai.tethr.app",
      subscriptionId: subscriptionId,
      token: purchaseToken
    });

    const purchase = response.data;
    const nowMs = Date.now();
    const isPremium = purchase.expiryTimeMillis && parseInt(purchase.expiryTimeMillis, 10) > nowMs;

    // Optional: Update the user's profile in the database (assuming you have a 'profiles' table)
    await supabaseClient
      .from('profiles')
      .upsert({ 
         id: user.id, 
         is_premium: isPremium, 
         premium_expiry: purchase.expiryTimeMillis,
         latest_purchase_token: purchaseToken
      });

    return new Response(
      JSON.stringify({ isPremium }),
      { headers: { "Content-Type": "application/json" } }
    )
  } catch (err) {
    console.error(err)
    return new Response(JSON.stringify({ error: "Internal Server Error" }), { status: 500 })
  }
})
