import json
import os

overlay = ""
tethr = ""

try:
    with open(r'C:\Users\tharik\.gemini\antigravity-ide\brain\367fe0c3-fbc1-48d1-b3a5-82687dc759bd\.system_generated\logs\transcript_full.jsonl', 'r', encoding='utf-8') as f:
        for line in f:
            try:
                data = json.loads(line)
                if 'tool_calls' in data:
                    for call in data['tool_calls']:
                        if call['name'] == 'write_to_file':
                            target = call['args'].get('TargetFile', '')
                            if 'OverlayManager.kt' in target:
                                overlay = call['args'].get('CodeContent', '')
                            elif 'TethrAccessibilityService.kt' in target:
                                tethr = call['args'].get('CodeContent', '')
            except:
                pass

    if overlay:
        with open('scratch/overlay_old.kt', 'w', encoding='utf-8') as f:
            f.write(overlay)
        print("Extracted OverlayManager")
    if tethr:
        with open('scratch/tethr_old.kt', 'w', encoding='utf-8') as f:
            f.write(tethr)
        print("Extracted TethrAccessibilityService")
except Exception as e:
    print(e)
