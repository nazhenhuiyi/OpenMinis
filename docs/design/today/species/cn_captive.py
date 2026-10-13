import json, time, urllib.request
UA = {"User-Agent": "MinisResearch/0.1 (species list for on-device ID; nazhenhuiyi@gmail.com)"}
out, page = [], 1
while True:
    url = f"https://api.inaturalist.org/v1/observations/species_counts?place_id=6903&captive=true&locale=zh-CN&per_page=500&page={page}"
    for a in range(5):
        try: d = json.load(urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=60)); break
        except Exception as e: print('retry', page, e); time.sleep(10)
    rs = d["results"]
    if not rs: break
    for r in rs:
        t = r["taxon"]
        out.append({"n": t["name"], "zh": t.get("preferred_common_name"), "c": r["count"], "g": t.get("iconic_taxon_name"), "id": t["id"], "anc": t.get("ancestor_ids")})
    print(page, len(out), flush=True); page += 1; time.sleep(1.2)
json.dump(out, open('/tmp/bc-data/cn_captive.json', 'w'), ensure_ascii=False)
