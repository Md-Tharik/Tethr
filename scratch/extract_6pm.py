import json
import sys

transcript_path = r"C:\Users\tharik\.gemini\antigravity-ide\brain\8530c733-4810-4cad-90d1-79ab33d6871e\.system_generated\logs\transcript_full.jsonl"

tethr_content = None
overlay_content = None

with open(transcript_path, 'r', encoding='utf-8') as f:
    for line in f:
        try:
            data = json.loads(line)
            created_at = data.get('created_at', '')
            if '2026-08-27T12' in created_at or '2026-08-27T11' in created_at or '2026-08-27T13' in created_at:
                if 'tool_calls' in data:
                    for call in data['tool_calls']:
                        if call['name'] in ['write_to_file', 'replace_file_content', 'multi_replace_file_content']:
                            args = call.get('args', {})
                            target = args.get('TargetFile', '')
                            if 'TethrAccessibilityService.kt' in target:
                                if 'CodeContent' in args:
                                    tethr_content = args['CodeContent']
                                    print(f"Found Tethr at {created_at}")
                            elif 'OverlayManager.kt' in target:
                                if 'CodeContent' in args:
                                    overlay_content = args['CodeContent']
                                    print(f"Found Overlay at {created_at}")
        except:
            pass

if tethr_content:
    with open('scratch/tethr_6pm.kt', 'w', encoding='utf-8') as f:
        f.write(tethr_content)
if overlay_content:
    with open('scratch/overlay_6pm.kt', 'w', encoding='utf-8') as f:
        f.write(overlay_content)
