import io
import json

path = "/home/colorgarden/campaign-server/config/mods/Campaign Server Side/config.json"

with io.open(path, encoding="utf-8") as handle:
    data = json.load(handle)

data["voteCooldown"] = 5

with io.open(path, "w", encoding="utf-8") as handle:
    json.dump(data, handle, indent=2, ensure_ascii=False)
    handle.write("\n")

print("voteCooldown =", data["voteCooldown"])
print("voteTimeout =", data["voteTimeout"])
print("voteDuration =", data["voteDuration"])
