import json
import re

log_path = r"C:\Users\tharik\.gemini\antigravity-ide\brain\8530c733-4810-4cad-90d1-79ab33d6871e\.system_generated\logs\transcript_full.jsonl"
latest_content = None

with open(log_path, 'r', encoding='utf-8') as f:
    for line in f:
        try:
            entry = json.loads(line)
            # Check if this is a tool response
            if entry.get("source") == "SYSTEM" and entry.get("type") == "TOOL_RESPONSE":
                content = entry.get("content", "")
                if "TethrAccessibilityService.kt`" in content and "Created At:" in content and "Total Lines:" in content:
                    latest_content = content
        except Exception as e:
            pass

if latest_content:
    with open("scratch/recovered_view.txt", "w", encoding="utf-8") as out:
        out.write(latest_content)
    print("Found and saved to scratch/recovered_view.txt")
else:
    print("Not found in logs")
