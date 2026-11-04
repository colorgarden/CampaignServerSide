import io
import json

path = "/home/colorgarden/campaign-server/config/mods/Campaign Server Side/config.json"

with io.open(path, encoding="utf-8") as handle:
    data = json.load(handle)

data["voteDuration"] = 15
data["voteCooldown"] = 2
data["afkEnabled"] = True
data["afkSeconds"] = 60

with io.open(path, "w", encoding="utf-8") as handle:
    json.dump(data, handle, indent=2, ensure_ascii=False)
    handle.write("\n")

for key in ("voteDuration", "voteTimeout", "voteCooldown", "votePercent", "voteMinPlayers", "afkEnabled", "afkSeconds"):
    print(key, "=", data.get(key))
