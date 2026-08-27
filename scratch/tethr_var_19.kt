@"
import json
import os

log_path = r"C:\Users\tharik\.gemini\antigravity-ide\brain\8530c733-4810-4cad-90d1-79ab33d6871e\.system_generated\logs\transcript_full.jsonl"
tethr_count = 0
overlay_count = 0

if not os.path.exists("scratch"):
    os.makedirs("scratch")

with open(log_path, 'r', encoding='utf-8') as f:
    for line in f:
        if 'class TethrAccessibilityService' in line and 'package com.example.tethr' in line:
            try:
                data = json.loads(line)
                content = ""
                if 'tool_calls' in data:
                    for call in data['tool_calls']:
                        args = call.get('args', {})
                        if 'CodeContent' in args:
                            content = args['CodeContent']
                        elif 'CommandLine' in args:
                            content = args['CommandLine']
                elif 'content' in data:
                    content = data['content']
                    
                if len(content) > 1000:
                    with open(f"scratch/tethr_var_{tethr_count}.kt", "w", encoding="utf-8") as out:
                        out.write(content)
                    tethr_count += 1
            except:
                pass
                
        if 'class OverlayManager' in line and 'package com.example.tethr' in line:
            try:
                data = json.loads(line)
                content = ""
                if 'tool_calls' in data:
                    for call in data['tool_calls']:
                        args = call.get('args', {})
                        if 'CodeContent' in args:
                            content = args['CodeContent']
                        elif 'CommandLine' in args:
                            content = args['CommandLine']
                elif 'content' in data:
                    content = data['content']
                    
                if len(content) > 1000:
                    with open(f"scratch/overlay_var_{overlay_count}.kt", "w", encoding="utf-8") as out:
                        out.write(content)
                    overlay_count += 1
            except:
                pass

print(f"Extracted {tethr_count} Tethr versions and {overlay_count} OverlayManager versions.")
"@ | Out-File -FilePath "extract_all_versions.py" -Encoding UTF8
C:\Users\tharik\AppData\Local\Microsoft\WindowsApps\python.exe extract_all_versions.py
