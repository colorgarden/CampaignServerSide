import io
import json
import sys

path = "/home/colorgarden/campaign-server/config/mods/Campaign Server Side/config.json"

with io.open(path, encoding="utf-8") as handle:
    data = json.load(handle)

data["requireSectorCapture"] = True

with io.open(path, "w", encoding="utf-8") as handle:
    json.dump(data, handle, indent=2, ensure_ascii=False)
    handle.write("\n")

print("requireSectorCapture =", data["requireSectorCapture"])
print("enforceSectorUnlock =", data.get("enforceSectorUnlock"))
print("researchStrict =", data.get("researchStrict"))
print("voteMinPlayers =", data.get("voteMinPlayers"))
print("voteDecision =", data.get("voteDecision"))
