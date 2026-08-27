import os

target_dir = r"c:\Users\tharik\OneDrive\Documents\tethr\app\src\main\java\com\example\tethr"

def fix_file(filepath):
    with open(filepath, 'r', encoding='utf-8') as f:
        content = f.read()

    changed = False
    if 'â€”' in content:
        content = content.replace('â€”', '—')
        changed = True
    if 'â†’' in content:
        content = content.replace('â†’', '→')
        changed = True
    if 'Ã—' in content:
        content = content.replace('Ã—', '×')
        changed = True
    if 'Â·' in content:
        content = content.replace('Â·', '·')
        changed = True
    if 'ï¿½' in content:
        content = content.replace('ï¿½', '—')
        changed = True

    if changed:
        with open(filepath, 'w', encoding='utf-8') as f:
            f.write(content)
        print(f"Fixed {filepath}")

for root, _, files in os.walk(target_dir):
    for file in files:
        if file.endswith('.kt'):
            fix_file(os.path.join(root, file))
