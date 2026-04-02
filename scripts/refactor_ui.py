import re

path = "android/app/src/main/kotlin/com/paul/sprintsync/SprintSyncApp.kt"
with open(path, "r", encoding="utf-8") as f:
    text = f.read()

# Make sure imports are there
if "com.paul.sprintsync.ui.components.*" not in text:
    text = text.replace("package com.paul.sprintsync\n", "package com.paul.sprintsync\n\nimport com.paul.sprintsync.ui.components.*\nimport com.paul.sprintsync.ui.theme.*\n")

# Replace unstyled Card + Column padding with SprintSyncCard
text = re.sub(r"Card \{\s*Column\(modifier = Modifier.padding\(\d+\.dp\)", r"SprintSyncCard {\n        Column(", text)
text = re.sub(r"Card \{\s*Column\(modifier = Modifier\n\s*\.fillMaxWidth\(\)\n\s*\.padding\(\d+\.dp\)", r"SprintSyncCard {\n        Column(\n            modifier = Modifier.fillMaxWidth()", text)
text = re.sub(r"Card \{\s*Row\(\s*modifier = Modifier\s*\n\s*\.fillMaxWidth\(\)\s*\n\s*\.padding\(\d+\.dp\)", r"SprintSyncCard {\n        Row(\n            modifier = Modifier.fillMaxWidth()", text)

# Metric Display (for live stats in AdvancedDetectionCard)
text = re.sub(r"Text\(\"([^\"]+):\s*\$\{[^\}]+\}\"\)", lambda m: f"MetricDisplay(label = \"{m.group(1)}\", value = {m.group(0).split('$', 1)[1][:-1]})", text)

# Bold titles to SectionHeader
text = re.sub(r"Text\(\"([^\"]+)\",\s*fontWeight = FontWeight\.SemiBold\)", r"SectionHeader(\"\1\")", text)
text = re.sub(r"Text\(\"([^\"]+)\",\s*fontWeight = FontWeight\.Bold\)", r"SectionHeader(\"\1\")", text)

# Connected devices card highlight
text = text.replace("Card {\n        Column(modifier = Modifier.padding(12.dp)", "SprintSyncCard(highlightIntent = if (discoveredEndpoints.isNotEmpty()) CardHighlightIntent.ACTIVE else CardHighlightIntent.NONE) {\n        Column(")

# StopWatch font
text = text.replace("Text(elapsedDisplay, style = MaterialTheme.typography.displayMedium)", "Text(elapsedDisplay, style = TabularMonospaceTypography)")

# Basic Buttons
text = re.sub(r"Button\(\s*onClick = ([^,]+),\s*enabled = ([^)]+),\s*\) \{\s*Text\(\"([^\"]+)\"\)\s*\}", r"PrimaryButton(text = \"\3\", onClick = \1, enabled = \2)", text)
text = re.sub(r"Button\(onClick = ([^,]+),\s*enabled = ([^)]+)\) \{\s*Text\(\"([^\"]+)\"\)\s*\}", r"PrimaryButton(text = \"\3\", onClick = \1, enabled = \2)", text)
text = re.sub(r"Button\(onClick = ([^,]+)\) \{\s*Text\(\"([^\"]+)\"\)\s*\}", r"PrimaryButton(text = \"\2\", onClick = \1)", text)

text = re.sub(r"OutlinedButton\(\s*onClick = ([^,]+),\s*enabled = ([^)]+),\s*\) \{\s*Text\(\"([^\"]+)\"\)\s*\}", r"SecondaryButton(text = \"\3\", onClick = \1, enabled = \2)", text)
text = re.sub(r"OutlinedButton\(onClick = ([^,]+),\s*enabled = ([^)]+)\) \{\s*Text\(\"([^\"]+)\"\)\s*\}", r"SecondaryButton(text = \"\3\", onClick = \1, enabled = \2)", text)
text = re.sub(r"OutlinedButton\(onClick = ([^,]+)\) \{\s*Text\(\"([^\"]+)\"\)\s*\}", r"SecondaryButton(text = \"\2\", onClick = \1)", text)

with open(path, "w", encoding="utf-8") as f:
    f.write(text)
