import json

with open(r'C:\Users\tharik\.gemini\antigravity-ide\brain\8530c733-4810-4cad-90d1-79ab33d6871e\.system_generated\logs\transcript_full.jsonl', 'r', encoding='utf-8') as f:
    for line in f:
        try:
            data = json.loads(line)
            if 'tool_calls' in data:
                for call in data['tool_calls']:
                    if call['name'] in ['write_to_file', 'replace_file_content', 'multi_replace_file_content']:
                        args = call.get('args', {})
                        if 'OverlayManager.kt' in args.get('TargetFile', ''):
                            print(f"Mod at {data['created_at']}")
                            if 'CodeContent' in args:
                                for l in args['CodeContent'].split('\n'):
                                    if 'rx =' in l or 'ry =' in l or 'textSize =' in l:
                                        print('  ' + l.strip())
                            if 'ReplacementChunks' in args:
                                for chunk in args['ReplacementChunks']:
                                    if 'ReplacementContent' in chunk:
                                        for l in chunk['ReplacementContent'].split('\n'):
                                            if 'rx =' in l or 'ry =' in l or 'textSize =' in l:
                                                print('  ' + l.strip())
        except Exception as e:
            pass
