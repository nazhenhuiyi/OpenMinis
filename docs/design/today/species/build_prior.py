"""species_prior.bin for SpeciesId (Table.Prior): province presence and month shares, row for row with species.tsv.

Absence only counts where the data can say so: a province with few iNaturalist
records, or whose list hit the API's 20,000-species cap, marks nobody absent.
A month whose list hit the cap (summer) says nothing exact about kinds below
it: they get half the smallest count on that list, not 0 — else a rarer
kind would look out of season in its own best months.
"""
import json, struct, numpy as np
D = '/tmp/bc-data'
CAP = 19500          # species_counts stops at 40 pages × 500 (a few names repeat, so just under 20,000)
MIN_SPECIES = 2000   # fewer kinds recorded than this: too thin to call anything absent
rows = [l.rstrip('\n').split('\t') for l in open(f'{D}/species/species.tsv')]
provinces = json.load(open(f'{D}/provinces.json'))
geo = json.load(open(f'{D}/geo.json'))
months = json.load(open(f'{D}/months.json'))
n, P = len(rows), len(provinces)
G = np.ones((n, P), np.uint8); Mo = np.zeros((n, 12), np.uint8)
used = []
for j, (pid, en, zh) in enumerate(provinces):
    g = geo.get(str(pid))
    if g is None or len(g) >= CAP or len(g) < MIN_SPECIES: continue
    used.append(zh)
    for i, r in enumerate(rows):
        if r[0] not in g: G[i, j] = 0
# A kind with no province records at all (pets added by hand, data gaps) is "everywhere": no penalty.
none = G.sum(1) == 0; G[none] = 1
# For a capped month, what a kind missing from its list is taken to have.
below = {m: (min(months[str(m)].values()) / 2 if len(months.get(str(m), {})) >= CAP else 0) for m in range(1, 13)}
print('capped months:', [m for m in below if below[m]], 'fill', {m: below[m] for m in below if below[m]})
for i, r in enumerate(rows):
    c = np.array([months.get(str(m), {}).get(r[0], below[m]) for m in range(1, 13)], float)
    if c.sum() < 10: continue  # too few sightings to say anything about seasons
    sm = 0.5 * c + 0.25 * (np.roll(c, 1) + np.roll(c, -1))
    Mo[i] = np.clip(np.round(sm / sm.max() * 255), 1, 255)
with open(f'{D}/species/species_prior.bin', 'wb') as f:
    f.write(struct.pack('<4sii', b'PRI1', n, P)); f.write(G.tobytes()); f.write(Mo.tobytes())
print(n, 'species;', len(used), 'provinces used:', ' '.join(used))
print(int((Mo.sum(1) > 0).sum()), 'with month data;', round((G.nbytes + Mo.nbytes) / 1e6, 2), 'MB')
