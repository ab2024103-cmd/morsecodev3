#!/usr/bin/env bash
# Generates the legacy (API 21-25) and round launcher bitmaps for §4.10 / §19.2:
# a squircle tile carrying the warm orange gradient #EF7D0F -> #F5A712 -> #FBBF24
# at 135 degrees, with the black Morse glyph (three bars, six dots, one long bar).
# Adaptive icons (API 26+) are vectors and are NOT generated here.
#
# Requires ImageMagick. Run from the repository root:  bash tools/genicons.sh
set -euo pipefail

RES="app/src/main/res"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# density -> canvas px
declare -A SIZES=( [mdpi]=48 [hdpi]=72 [xhdpi]=96 [xxhdpi]=144 [xxxhdpi]=192 )

for density in "${!SIZES[@]}"; do
  S=${SIZES[$density]}
  D=$((S * 2))
  OUT="$RES/mipmap-$density"
  mkdir -p "$OUT"

  # 135-degree three-stop gradient, cropped to the canvas.
  convert -size 1x200 gradient:'#EF7D0F-#F5A712' \
          -size 1x200 gradient:'#F5A712-#FBBF24' -append \
          -resize "${D}x${D}!" -alpha off -background '#F5A712' -rotate -45 \
          -gravity center -crop "${S}x${S}+0+0" +repage "$TMP/grad.png"

  # Squircle mask: 22% corner radius (§4.9/§4.10).
  R=$(awk -v s="$S" 'BEGIN{printf "%d", s*0.22}')
  convert -size "${S}x${S}" xc:none -fill white \
          -draw "roundrectangle 0,0,$((S-1)),$((S-1)),$R,$R" "$TMP/mask.png"
  convert "$TMP/grad.png" "$TMP/mask.png" \
          -alpha off -compose CopyOpacity -composite "$TMP/tile.png"

  # Circular mask for the round variant.
  convert -size "${S}x${S}" xc:none -fill white \
          -draw "circle $(awk -v s="$S" 'BEGIN{printf "%.1f,%.1f %.1f,%.1f", (s-1)/2,(s-1)/2,(s-1)/2,0}')" \
          "$TMP/maskr.png"
  convert "$TMP/grad.png" "$TMP/maskr.png" \
          -alpha off -compose CopyOpacity -composite "$TMP/tile_round.png"

  # Glyph, mapped from the vector's 108-unit space: x 21..87, y 33.2..74.8.
  # The glyph spans 62% of the canvas width on the legacy tile.
  read -r K OX OY <<<"$(awk -v s="$S" 'BEGIN{gw=s*0.62;k=gw/66;gh=41.6*k;printf "%.5f %.5f %.5f", k, (s-gw)/2, (s-gh)/2}')"
  map_x() { awk -v v="$1" -v k="$K" -v o="$OX" 'BEGIN{printf "%.2f", (v-21)*k+o}'; }
  map_y() { awk -v v="$1" -v k="$K" -v o="$OY" 'BEGIN{printf "%.2f", (v-33.2)*k+o}'; }
  len()   { awk -v v="$1" -v k="$K" 'BEGIN{printf "%.2f", v*k}'; }

  DRAW=""
  for x in 21 32.6 44.2; do
    x2=$(awk -v x="$x" 'BEGIN{printf "%.2f", x+6.1}')
    DRAW="$DRAW rectangle $(map_x "$x"),$(map_y 33.2) $(map_x "$x2"),$(map_y 60.1)"
  done
  RAD=$(len 3.05)
  for cy in 40.85 52.45; do
    for cx in 60.65 72.25 83.85; do
      px=$(map_x "$cx"); py=$(map_y "$cy")
      edge=$(awk -v p="$px" -v r="$RAD" 'BEGIN{printf "%.2f", p+r}')
      DRAW="$DRAW circle $px,$py $edge,$py"
    done
  done
  DRAW="$DRAW rectangle $(map_x 21),$(map_y 67.5) $(map_x 87),$(map_y 74.8)"

  convert "$TMP/tile.png"       -fill black -draw "$DRAW" "$OUT/ic_launcher.png"
  convert "$TMP/tile_round.png" -fill black -draw "$DRAW" "$OUT/ic_launcher_round.png"
  echo "wrote $OUT/ic_launcher.png and ic_launcher_round.png (${S}px)"
done
