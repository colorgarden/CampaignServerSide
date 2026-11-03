import io
import json

path = "/home/colorgarden/campaign-server/config/mods/Campaign Server Side/config.json"

with io.open(path, encoding="utf-8") as handle:
    data = json.load(handle)

data["voteMinPlayers"] = 1
data["voteDecision"] = "majority"
for key in ("voting", "voteSector", "voteResearch", "votePlanet", "voteDestination", "voteRedirect"):
    data[key] = True

with io.open(path, "w", encoding="utf-8") as handle:
    json.dump(data, handle, indent=2, ensure_ascii=False)
    handle.write("\n")

for key in ("voting", "voteMinPlayers", "voteDecision", "votePercent", "voteTimeout"):
    print(key, "=", data.get(key))
