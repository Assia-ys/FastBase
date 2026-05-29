"""
FastBase — Graphiques de soutenance
Données sources : SOUTENANCE_PERF.md  (mesures réelles + estimés documentés)
Génère         : soutenance_perf_graphs.png / .svg
"""
import matplotlib.pyplot as plt
import matplotlib.ticker as ticker
import numpy as np
import os

# ── Palette ───────────────────────────────────────────────────────────────────
ROUGE  = '#c62828'
ORANGE = '#e65100'
VERT   = '#2e7d32'
BLEU   = '#1565c0'
GRIS   = '#546e7a'

fig = plt.figure(figsize=(20, 11))
fig.suptitle(
    'FastBase — Évolution des performances par opération\n'
    'Dataset : NYC Yellow Taxi 70,5 M lignes  •  12 CPUs  •  16 GB RAM  •  Java 17',
    fontsize=13, fontweight='bold'
)

# ══════════════════════════════════════════════════════════════════════════════
# 1. LOAD — évolution par palier
#    Mesuré (Phase 2+3+4 sans P15+P16) vs estimé avec P15+P16 (÷2,5)
#    Source : table "Par palier" — SOUTENANCE_PERF.md
# ══════════════════════════════════════════════════════════════════════════════
ax1 = fig.add_subplot(2, 3, 1)

scales_M = [4, 10, 20, 30, 40, 50, 70]

load_mesure  = [2_557, 8_684, 18_511, 29_504, 41_931, 54_944, 82_484]   # ms
load_p15p16  = [int(x / 2.5) for x in load_mesure]                       # estimé P16 ×2,5

ax1.plot(scales_M, [x / 1000 for x in load_mesure],
         'o-', color=ROUGE, linewidth=2.5, markersize=8,
         label='Sans P15+P16 (mesuré)')
ax1.plot(scales_M, [x / 1000 for x in load_p15p16],
         's--', color=VERT, linewidth=2.5, markersize=8,
         label='Avec P15+P16 (estimé ÷ 2,5)')

# Annotation gain à 70 M
y_av = load_mesure[-1] / 1000
y_ap = load_p15p16[-1] / 1000
ax1.annotate('', xy=(70.5, y_ap), xytext=(70.5, y_av),
             arrowprops=dict(arrowstyle='<->', color=BLEU, lw=2.2))
ax1.text(63, (y_av + y_ap) / 2, '÷ 2,5',
         color=BLEU, fontsize=11, fontweight='bold', va='center', ha='right')

ax1.set_title('LOAD Parquet', fontsize=12, fontweight='bold')
ax1.set_xlabel('Lignes (millions)')
ax1.set_ylabel('Temps cumulé (s)')
ax1.legend(fontsize=8, loc='upper left')
ax1.grid(True, alpha=0.3)
ax1.set_xlim(0, 80)

# ══════════════════════════════════════════════════════════════════════════════
# 2. SELECT — avant / après P3  (4 M lignes synthétiques)
#    Source : "SELECT 4M : 561ms → 197ms (×2,8)" — SOUTENANCE_PERF.md §P3
# ══════════════════════════════════════════════════════════════════════════════
ax2 = fig.add_subplot(2, 3, 2)

labels_sel = ['v0\n(avant tout)', 'Après P3\n(HashMap)']
vals_sel   = [561, 197]

bars2 = ax2.bar(labels_sel, vals_sel, color=[ROUGE, VERT], width=0.45,
                zorder=3, edgecolor='white', linewidth=1.5)
ax2.bar_label(bars2, labels=[f'{v} ms' for v in vals_sel],
              padding=7, fontweight='bold', fontsize=12)
ax2.text(0.5, 0.82, '× 2,8 plus rapide', transform=ax2.transAxes,
         ha='center', fontsize=14, color=VERT, fontweight='bold')
ax2.text(0.5, 0.70, 'LinkedHashMap → HashMap\n(−16 M mises à jour de pointeurs)',
         transform=ax2.transAxes, ha='center', fontsize=8, color=GRIS)
ax2.set_title('SELECT — 4 M lignes', fontsize=12, fontweight='bold')
ax2.set_ylabel('Temps (ms)')
ax2.set_ylim(0, 750)
ax2.grid(True, alpha=0.3, axis='y', zorder=0)

# ══════════════════════════════════════════════════════════════════════════════
# 3. ORDER BY / TOP-10 — 4 M lignes
#    2 groupes de 2 barres : ORDER BY (P6+P7) et TOP-10 (P8)
#    Source : Bilan Phase 1 — SOUTENANCE_PERF.md
# ══════════════════════════════════════════════════════════════════════════════
ax3 = fig.add_subplot(2, 3, 3)

groupes  = ['ORDER BY\n(tri complet)', 'TOP-10\n(LIMIT 10)']
avant    = [5_909, 3_011]
apres    = [1_489,   100]   # ORDER BY après P6+P7, TOP-10 après P8

x     = np.arange(len(groupes))
width = 0.32
b_av3 = ax3.bar(x - width / 2, avant, width, label='Avant (v0)',
                color=ROUGE, zorder=3, edgecolor='white')
b_ap3 = ax3.bar(x + width / 2, apres, width, label='Après P6+P7 / P8',
                color=VERT,  zorder=3, edgecolor='white')

ax3.bar_label(b_av3, labels=[f'{v:,} ms' for v in avant],
              padding=4, fontsize=9, fontweight='bold')
ax3.bar_label(b_ap3, labels=[f'{v:,} ms' for v in apres],
              padding=4, fontsize=9, fontweight='bold')

# Gain × au-dessus de chaque groupe
for i, (av, ap) in enumerate(zip(avant, apres)):
    ax3.text(i, max(av, ap) + 250, f'× {av/ap:.0f}',
             ha='center', fontsize=14, color=BLEU, fontweight='bold')

ax3.set_title('ORDER BY / TOP-10 — 4 M lignes', fontsize=12, fontweight='bold')
ax3.set_ylabel('Temps (ms)')
ax3.set_xticks(x)
ax3.set_xticklabels(groupes, fontsize=10)
ax3.set_ylim(0, 8_000)
ax3.legend(fontsize=9)
ax3.grid(True, alpha=0.3, axis='y', zorder=0)

# ══════════════════════════════════════════════════════════════════════════════
# 4. GROUP BY — avant / après P12+P14, par palier
#    Avant : estimé séquentiel, ancré sur "~3 500 ms à 70 M" (SOUTENANCE_PERF.md §P12)
#    Après : mesuré R1 GROUP BY payment_type
# ══════════════════════════════════════════════════════════════════════════════
ax4 = fig.add_subplot(2, 3, 4)

# Estimé séquentiel (1 thread, ancrage 70 M ≈ 3 500 ms linéaire)
gb_avant = [int(3_500 * m / 70) for m in scales_M]   # [200, 500, 1000, 1500, 2000, 2500, 3500]
# Mesuré R1 (Phase 4 P9+P12+P14)
gb_apres = [188, 178, 253, 541, 563, 556, 708]

ax4.plot(scales_M, gb_avant, 'o-', color=ROUGE, linewidth=2.5, markersize=8,
         label='Avant P12+P14 (séquentiel, estimé)')
ax4.plot(scales_M, gb_apres, 's-', color=VERT, linewidth=2.5, markersize=8,
         label='Après P12+P14 (mesuré)')

ax4.set_title('GROUP BY payment_type (R1)', fontsize=12, fontweight='bold')
ax4.set_xlabel('Lignes (millions)')
ax4.set_ylabel('Temps (ms)')
ax4.legend(fontsize=8)
ax4.grid(True, alpha=0.3)
ax4.text(0.5, 0.88, '× 4,9 plus rapide à 70 M lignes',
         transform=ax4.transAxes, ha='center', fontsize=11, color=VERT, fontweight='bold')

# ══════════════════════════════════════════════════════════════════════════════
# 5. WHERE + GROUP BY — avant / après P14  (70 M lignes)
#    Source : "R2 : 3 471ms → 1 324ms (×2,6)" et "R3 : 3 073ms → 634ms (×4,8)"
#             SOUTENANCE_PERF.md §P14
# ══════════════════════════════════════════════════════════════════════════════
ax5 = fig.add_subplot(2, 3, 5)

ops_where = ['R2 — WHERE + GB\n(11 groupes)', 'R3 — WHERE + GB\n(261 groupes)']
avant_p14 = [3_471, 3_073]
apres_p14 = [1_324,   634]

x5    = np.arange(len(ops_where))
w5    = 0.32
b_av5 = ax5.bar(x5 - w5 / 2, avant_p14, w5,
                label='Avant P14 (2 passes séquentielles)', color=ROUGE,
                zorder=3, edgecolor='white')
b_ap5 = ax5.bar(x5 + w5 / 2, apres_p14, w5,
                label='Après P14 (passe unique parallèle)', color=VERT,
                zorder=3, edgecolor='white')

ax5.bar_label(b_av5, labels=[f'{v:,} ms' for v in avant_p14], padding=4, fontsize=9, fontweight='bold')
ax5.bar_label(b_ap5, labels=[f'{v:,} ms' for v in apres_p14], padding=4, fontsize=9, fontweight='bold')

for i, (av, ap) in enumerate(zip(avant_p14, apres_p14)):
    ax5.text(i, max(av, ap) + 200, f'× {av/ap:.1f}',
             ha='center', fontsize=14, color=BLEU, fontweight='bold')

ax5.set_title('WHERE + GROUP BY — 70 M lignes\n(P14 : filtre inline dans boucle parallèle)',
              fontsize=11, fontweight='bold')
ax5.set_ylabel('Temps (ms)')
ax5.set_xticks(x5)
ax5.set_xticklabels(ops_where, fontsize=9)
ax5.set_ylim(0, 4_600)
ax5.legend(fontsize=8)
ax5.grid(True, alpha=0.3, axis='y', zorder=0)

# ══════════════════════════════════════════════════════════════════════════════
# 6. Tableau récapitulatif des gains
# ══════════════════════════════════════════════════════════════════════════════
ax6 = fig.add_subplot(2, 3, 6)
ax6.axis('off')

recap = [
    ['SELECT 4M',            '561 ms',     '197 ms',    '× 2,8'],
    ['ORDER BY 4M',          '5 909 ms',   '1 489 ms',  '× 4,0'],
    ['TOP-10 4M',            '3 011 ms',   '100 ms',    '× 30'],
    ['GROUP BY 70M',         '~3 500 ms',  '708 ms',    '× 4,9'],
    ['WHERE+GB R2 70M',      '3 471 ms',   '1 324 ms',  '× 2,6'],
    ['WHERE+GB R3 70M',      '3 073 ms',   '634 ms',    '× 4,8'],
    ['LOAD 70M (+ P15+P16)', '~100 s',     '~33 s',     '× 2,5 ¹'],
    ['Mémoire 50M lignes',   '7 600 MB',   '4 400 MB',  '− 42 %'],
]

tbl = ax6.table(
    cellText=recap,
    colLabels=['Opération', 'Avant', 'Après', 'Gain'],
    cellLoc='center',
    loc='center',
    bbox=[0, 0.08, 1, 0.92],
)
tbl.auto_set_font_size(False)
tbl.set_fontsize(9)

for j in range(4):                          # en-tête
    c = tbl[0, j]
    c.set_facecolor(BLEU)
    c.set_text_props(color='white', fontweight='bold')

for i in range(1, len(recap) + 1):         # lignes alternées + colonne gain
    for j in range(4):
        tbl[i, j].set_facecolor('#f0f4f8' if i % 2 == 0 else 'white')
    tbl[i, 3].set_facecolor('#e8f5e9')
    tbl[i, 3].set_text_props(color=VERT, fontweight='bold')

ax6.set_title('Récapitulatif des gains', fontsize=12, fontweight='bold', pad=10)
ax6.text(0.5, 0.02,
         '¹ P15 (ColumnReader) + P16 (pipeline row groups parallèles) — valeur estimée',
         transform=ax6.transAxes, ha='center', fontsize=7, color=GRIS, style='italic')

# ── Export ────────────────────────────────────────────────────────────────────
plt.tight_layout(rect=[0, 0, 1, 0.93])

out_dir = os.path.dirname(os.path.abspath(__file__))
out_png = os.path.join(out_dir, 'soutenance_perf_graphs.png')
out_svg = os.path.join(out_dir, 'soutenance_perf_graphs.svg')
plt.savefig(out_png, dpi=150, bbox_inches='tight')
plt.savefig(out_svg, bbox_inches='tight')
print(f"Graphiques générés :\n  {out_png}\n  {out_svg}")
plt.show()