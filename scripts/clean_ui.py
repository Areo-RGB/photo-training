import re

path = "android/app/src/main/kotlin/com/paul/sprintsync/SprintSyncApp.kt"
with open(path, "r", encoding="utf-8") as f:
    text = f.read()

text = text.replace("Column(, ", "Column(")
text = text.replace("TextPrimaryButton(text = \\\"Connect\\\", onClick = { onConnect(endpointId) }, enabled = !setupBusy)", "TextButton(onClick = { onConnect(endpointId) }, enabled = !setupBusy) { Text(\"Connect\") }")
text = re.sub(r'SectionHeader\(\\"([^"]+)\\"\)', r'SectionHeader("\1")', text)
text = re.sub(r'PrimaryButton\(text = \\"([^"]+)\\"', r'PrimaryButton(text = "\1"', text)
text = re.sub(r'SecondaryButton\(text = \\"([^"]+)\\"', r'SecondaryButton(text = "\1"', text)

with open(path, "w", encoding="utf-8") as f:
    f.write(text)
