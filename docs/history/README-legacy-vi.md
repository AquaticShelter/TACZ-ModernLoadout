# MW Loadout — Forge 1.20.1

Hệ thống loadout kiểu **Modern Warfare 2019** cho TACZ: 9 slot hotbar admin toàn quyền cấu hình,
gunsmith phụ kiện, 2 cột perk, đạn dự trữ tự nhận diện theo data súng, rule chỉnh sửa MW19,
class không giới hạn lưu vĩnh viễn — **CHỈ loadout system**, không matchmaking, không scoreboard,
không đụng armor.

Cài **cả client lẫn server**. Yêu cầu: TACZ (bắt buộc), LRTactical (tùy chọn — chỉ cần nếu dùng
các entry melee/throwable/consumable mặc định).

## Người chơi

- **Phím L**, nút **LOADOUT** góc trên-trái menu Esc, hoặc `/mwloadout` → mở màn hình class.
- Rail trái: các class đã lưu (mặc định tối đa 10 — admin chỉnh được). Đổi tên tự do.
- Click slot → picker có **tab category + thanh tìm kiếm**; súng có nút **CUSTOMIZE** mở gunsmith
  (SCOPE/MUZZLE/STOCK/GRIP/LASER/EXTENDED MAG).
- Hàng perk: mỗi cột chọn 1, click perk đang chọn để bỏ.
- **SAVE** = lưu (luôn được, kể cả đang combat). **EQUIP** = lưu + mặc ngay nếu cửa sổ chỉnh sửa
  cho phép; nếu không, class được nhớ và **tự mặc khi hồi sinh** (toast báo rõ) — đúng MW19.
- Khi không được chỉnh: GUI thành **read-only** kèm lý do ("Đang chiến đấu - chỉnh được sau Xs").

### Khi nào được đổi loadout (mặc định — admin chỉnh trong RULES)

| Điều kiện | |
|---|---|
| Creative / Spectator | luôn luôn |
| Server difficulty = **Peaceful** | luôn luôn (công tắc custom-game: hạ peaceful cho cả server build class thoải mái, nâng lên lại là khóa) |
| Trong **10s** sau hồi sinh | ✔ |
| **45s** không nhận sát thương | ✔ |
| Đứng trong **edit zone** admin đặt | ✔ |

Hồi sinh luôn restock đúng class đang active: **clear sạch rồi dựng lại** — không có đường dupe.

## Đạn dự trữ (Dynamic Ammo — mặc định BẬT)

Reserve **tự tính từ dung lượng băng đạn thật của khẩu súng** (đọc từ index TACZ của chính khẩu
súng — mọi gunpack tự hoạt động — có tính cả Extended Mag) nhân theo tier, không còn map
category/override thủ công nào chen vào nữa:

- Shotgun dưới 10 viên → **x8** (rule riêng cho shotgun, không dùng tier chung bên dưới)
- Dưới 5 viên (mọi category khác) → **x5**
- Dưới 50 viên → **x3**
- Từ 50 viên trở lên → **x2**
- Có perk **Fully Loaded** → **nhân thêm x4** (`fullyLoadedMultiplier`, tự chỉnh trong RULES) vào
  ĐÚNG multiplier tier đã chọn ở trên, không thay thế nó — shotgun 2 viên (tier x8) mang Fully
  Loaded thành x8×4=x32, không tụt xuống x4 phẳng

Ví dụ: Glock 17 (18 viên) → 18×3 = 54. Súng đó lắp Extended Mag lên 50 viên → 50×2 = 100. Shotgun
8 viên → 8×8 = 64. Tier tự chỉnh trong RULES (`dynamicAmmoTiers` — floor/ceiling/category/multiplier,
không cần sửa code). Tắt `dynamicAmmoEnabled` để quay lại bảng cố định theo category
(`ammoByCategory`) cho mọi súng — bảng này cũng là fallback khi TACZ không đọc được dung lượng băng
đạn của một khẩu súng. Đạn nằm ở inventory chính, không chiếm hotbar.

**Ghi đè multiplier** (2 tầng, ngoài tier ở trên), từ cụ thể nhất tới chung nhất:
1. **Riêng từng khẩu** — field `ammoMultiplier` ngay trên entry súng trong tab CATALOG, thắng tất cả.
2. **Theo category** — `categoryMultipliers` trong RULES (vd `sg=10` → mọi shotgun luôn x10, bỏ qua
   hẳn range tier <10/<50 của shotgun), thắng tier nhưng thua field riêng ở (1).

Không set gì thì rơi về tier như bình thường. Fully Loaded luôn nhân thêm `fullyLoadedMultiplier`
vào bất kể multiplier cuối cùng đến từ tier, category hay riêng khẩu súng.

## Perk (mặc định — sửa/thêm trong tab PERKS)

| Cột 1 | Hiệu ứng |
|---|---|
| **Overkill** | Slot 2 thành primary thứ hai (pistol/launcher biến mất), cấm trùng súng |
| **Quick Fix** | Regeneration vô hạn |
| **Fragiles** | Strength IV vô hạn |

| Cột 2 | Hiệu ứng |
|---|---|
| **Fast Hands** | reload_speed 1.35 (cần gunsmithlib — thiếu thì tự skip) |
| **Battle Hardened** | +20 armor không cần giáp |
| **Scavenger** | Giết player: +10-20% đạn khẩu đang cầm; mỗi 30s +1 lethal/tactical |
| **Blood Rush** (Double Time) | Movement speed 0.111 |

Hiệu ứng là JSON data — admin thêm perk mới với `attribute`/`mob_effect` bất kỳ không cần code.

## Admin

`/mwloadout admin` (permission 2) → panel 4 tab:
- **SLOTS**: từng slot — tên, mô tả, icon, categories, whitelist/blacklist, số lượng, default,
  required/locked/hidden/enabled, vị trí hotbar. Slot extra1-3 có sẵn (đang tắt) — thêm item vào
  CATALOG với category `extra1`..., bật slot là players chọn được.
- **CATALOG**: kho item/súng — thêm BẤT KỲ item nào (id + NBT + số lượng), súng tự quét từ mọi
  gunpack (chỉ thêm mới, không đè chỉnh sửa của bạn).
- **PERKS**: thêm/sửa perk, đổi cột, effects JSON.
- **RULES**: cửa sổ chỉnh sửa, peaceful switch, số class tối đa, bảng đạn, override đạn theo gun,
  perk columns, edit zones.

Mọi thay đổi lưu `world/data/mwloadout/*.json` + **đồng bộ ngay cho toàn bộ player online**.
Sửa file tay → `/mwloadout reload`.

## Build

```bash
./gradlew build
```
Output: `build/libs/mwloadout-1.20.1-1.1.0.jar`

## Changelog

### v1.0.1
- **[Fix nghiêm trọng] Lựa chọn biến mất sau khi chọn**: Minecraft gọi lại `init()` của màn hình
  MỖI LẦN quay về từ picker (và mỗi lần resize cửa sổ) — bản đầu nạp lại bản lưu cũ ngay tại đó,
  âm thầm vứt hết những gì vừa chọn (kể cả tên class vừa gõ). Giờ bản nháp chỉ được tạo đúng 1 lần
  mỗi lần mở màn hình (hoặc khi chủ động đổi class), `init()` chỉ dựng lại widget quanh state còn
  nguyên. Đây là gốc của "select được nhưng không hiện" + "không tùy chỉnh được gì".
- **[Fix] Layout tràn màn hình, mất nút SAVE/EQUIP**: layout tile cũ tràn khỏi chiều cao 240px ở
  GUI scale phổ biến. Đổi sang hàng slot gọn (26px) trong vùng CUỘN được + footer ghim cố định —
  vừa mọi GUI scale.
- **Phụ kiện lọc ĐÚNG theo súng**: rail loại phụ kiện giờ chỉ hiện các loại mà chính gunpack của
  khẩu đó khai báo (`getAllowAttachments`), danh sách ứng viên lọc qua chính
  `AllowAttachmentTagMatcher` của TACZ — hết cảnh mọi súng hiện mọi phụ kiện kể cả không tương
  thích. (API đối chiếu từ decompile TaczItemCache của CodMatchmaking gốc.)
- **Bảng chỉ số như mod gốc**: panel stat cố định bên phải ở cả picker súng (DMG/HS/PIERCE/
  PELLETS/RPM/MAG/chế độ bắn) lẫn gunsmith (modifier từng phụ kiện: addend/%/multiplier, màu
  xanh/đỏ tự đảo cho recoil/inaccuracy/weight/knockback đúng convention gốc, weight, ext mag).
- **Icon item thật ở mọi nơi**: icon dựng theo pattern client-index đã kiểm chứng của mod gốc
  (GunId/AttachmentId NBT + getClientGunIndex/getClientAttachmentIndex) — súng TACZ, phụ kiện,
  item LRTactical đều hiện đúng hình, có cache không churn.
- **Âm thanh + animation**: click/tab/hover (pitch khác nhau, cùng bộ âm vanilla để resource pack
  theme được), màn hình mở fade ~180ms + vệt hairline vàng quét ngang, hover fade giữ nguyên.
  Vị trí KHÔNG animate — hit-test luôn trung thực.

### v1.1.0 — "GUNSMITH MEMORY" update
**Nguồn dữ liệu & icon**
- **Quét creative tab thay cho index** (`/mwloadout weaponscan`, tự chạy khi server start): súng
  `hidden=true` không bao giờ lọt vào picker nữa; item LRTactical lấy đúng base item + NBT + tên
  thật từ stack trong tab — **hết vĩnh viễn icon tím đen** của melee/consumable/throwable. Entry
  seed cũ bị hỏng tự "chữa lành" theo NBT id, giữ nguyên chỉnh sửa category/count của admin. Súng
  biến mất khỏi tab (bị ẩn/gỡ pack) tự disable chứ không xóa.
- Tên súng lấy từ `getPojo().getName()` của chính gunpack (đúng cách mod gốc làm).

**GUI đại tu theo MW19 thật**
- **Theme xám**: viền idle #3A3E44, active/selected trắng xám #C8CDD2 — **hết vàng mặc định**;
  toàn bộ màu config được trong `config/mwloadout-client.toml` (borderColor / borderActiveColor /
  accentColor / hoverColor).
- **Hover dạng LƯỚI XANH** đúng chất MW19: fill xanh nhạt + đường lưới mịn 5px trong tile đang
  hover — thuần fill, không texture, không allocation.
- **Màn hình loadout bố cục card**: rail lớp bên trái + hàng "+ LỚP MỚI", cột card giữa —
  Primary/Secondary là card cao render **HUD silhouette của chính khẩu súng** (texture HUD từ
  gunpack, y hệt TACZ vẽ ở góc màn hình; tắt được trong client config, tự fallback icon) + nút
  **GUNSMITH** cắm ngay cạnh card; card PERKS 3 ô icon; card trang bị gọn tên + icon item thật.
- **Chọn súng = danh sách DỌC kiểu MW19**: mỗi súng một hàng HUD silhouette + tên + loại + cỡ
  đạn, nút GUNSMITH trên TỪNG hàng, **cuộn mượt** (bánh xe đặt đích, khung đuổi theo), lựa chọn
  hiện tại ghim đầu danh sách. Item thường vẫn hàng icon thật + tên.
- **Search lọc theo cả cỡ đạn**: gõ "556" ra mọi súng 5.56; vẫn khớp tên/id/category.
- Live-invalid: đổi perk (Overkill) → card vũ khí không hợp lệ đỏ "THAY THẾ" NGAY, không cần Esc.
- Âm hover có cooldown 90ms toàn cục — hết tiếng tạch liên thanh.

**Gunsmith memory — 4 bản độ mỗi súng**
- Mỗi khẩu súng nhớ **4 build phụ kiện** (BẢN ĐỘ 1-4) theo TÀI KHOẢN người chơi (không theo lớp):
  chỉnh trong gunsmith, bấm tab đổi bản, XÁC NHẬN là lưu server-side (validate từng phụ kiện).
  Lần sau chọn lại khẩu đó ở bất kỳ đâu — bản độ đang active tự đi theo, không phải mod lại.

**Perk: 3 cột xếp theo Modern Warfare 4** (config cũ tự được chuyển 1 lần; chỉ đổi thứ còn là giá trị mặc định)
- Cột 1 (nhận diện & streak): Quick Fix, Overkill, **High Alert** (chevron nhấp nháy chỉ hướng kẻ đang
  nhìn mình; người mang Tracker không bị lộ), Ghost, Tune Up, Killchain.
- Cột 2 (di chuyển, thao tác, kinh tế): Hardline, **Blood Rush** (tên cũ Double Time), **Fast Hands**
  (tên cũ Talk To Hand / Sleight Of Hands), **Ninja** (âm thanh di chuyển nhỏ 90%, client-side, hợp
  Presence Footsteps / Tactical Footsteps), **Shrapnel** (giảm sát thương nổ, +lethal & tactical).
- Cột 3 (sống sót & trang bị): Battle Hardened (giảm hiệu ứng xấu 90%, miễn nhiễm Glowing trừ Tracker,
  giáp +4), Scavenger, Tracker, **Fragiles** (đòn trúng player khác chặn hồi máu của họ 2s — không
  còn Wither), **Fully Loaded** (x2 đạn dự trữ).
- **Shield Up** chỉ có ở Specialist cột 3 (`perkSlot: "specialist3"`), không xuất hiện ở Perk 3.

**Cân bằng & chống lạm dụng**
- Item loadout gắn tag ẩn: **không Q-drop được** (tự về túi) và **không rơi khi chết** (respawn
  restock lại — chặn đứng vòng dupe). Tắt được: `preventDropManaged` trong RULES.
- **Fix "2 viên ném ra còn 1"**: item maxStackSize nhỏ hơn count giờ tràn phần dư thành stack
  phụ trong túi chính thay vì bị clamp mất.
- Overkill: picker slot phụ **ẩn luôn khẩu đang ở slot 1**.

**Admin & hệ thống**
- **Đạn riêng từng khẩu**: field `ammoOverride` ngay trên entry súng trong tab CATALOG (0 = theo
  bảng category) — không cần mò map trong RULES nữa.
- **Nút TỪ TAY CẦM** (tab CATALOG): cầm item/súng bất kỳ → điền sẵn id + NBT đầy đủ + tên + số
  lượng vào form. Panel admin refresh trực tiếp khi có thay đổi (kể cả từ admin khác).
- **Progression nền móng (MẶC ĐỊNH TẮT)**: bật `progressionEnabled` trong RULES → kill người chơi
  +XP (`xpPerKill`, level = xp/1000+1), entry có `unlockLevel` sẽ khóa dưới cấp — rework từ ý
  tưởng level của codmatchmaking, không tracker/scoreboard.
- **Hồ sơ người chơi chuyển về `config/mwloadout/players/`** — giữ tiến trình qua reset world
  (kiểu saveandloadinventories); registry admin vẫn theo từng world.
- Lệnh mới: `weaponscan`, `zone add <bán kính>/list/remove`, `resetperks`, `help` (hướng dẫn đầy
  đủ ngay trong game).
- Lang EN/VI viết lại sát ngôn ngữ MW19 ("triển khai", "Reserve rounds", "Confirm"...).

### v1.1.0 hotfix — build fail "Could not find net.minecraft:client:1.20.1"
Nguyên nhân: plugin Parchment librarian khai báo version trôi nổi `1.+` — bản mới nhất không còn
tương thích, mapping channel `parchment` không đăng ký được nên Gradle xin thẳng artifact
minecraft CHƯA MAP (không có hậu tố `_mapped_`) và không nơi nào có nó. Fix: **bỏ hẳn Parchment**
— chuyển sang mapping `official` (mojmap) tích hợp sẵn trong ForgeGradle, zero plugin ngoài,
source không đổi một ký tự (parchment chỉ thêm tên tham số). `forge_version` đồng bộ lên 47.2.20
(bản đã build thành công ở project quicktactical trên chính máy bạn).

Build lại:
```powershell
.\gradlew --stop
.\gradlew build --refresh-dependencies
```
(Nếu vẫn lỗi cấu hình cũ dính cache: xóa thư mục `.gradle` TRONG project rồi build lại.)

### v1.1.0 hotfix 2 — compile fix GunHud
`ClientGunIndex.getHUDTexture()` không tồn tại trong TACZ 1.1.8 — texture HUD nằm trong DISPLAY
của từng khẩu súng (file display trong gunpack zip, đúng như phỏng đoán). Đã sửa theo chain thật
mà chính GunHudOverlay của TACZ dùng (xác minh trực tiếp source 1.1.8 trên GitHub):
`getClientGunIndex -> getDefaultDisplay -> GunDisplayInstance.getHUDTexture`. Đồng thời audit
toàn bộ ~25 API call TACZ còn lại của mod với cây source 1.1.8 — tất cả khớp chính xác
(import path, chữ ký method, NBT key "GunId"/"AttachmentId").

### v1.1.1 — "GUNSMITH MODEL" update
- **Tên súng THẬT thay vì `item.tacz.modern_kinetic_gun`**: server không có lang client nên
  scanner chỉ lưu được key thô — giờ CLIENT tự resolve lúc vẽ: súng lấy tên từ chính gunpack
  (`ClientGunIndex.getName()`, đúng thứ TACZ hiển thị) + I18n; item khác resolve key qua I18n.
  Có cache, search khớp cả tên đã resolve.
- **HUD súng hết lép**: TACZ vẽ HUD ở tỉ lệ 39x13 (soi trực tiếp GunHudOverlay) — đổi từ 2:1
  sang đúng 3:1, khu HUD trong hàng chọn súng nới 76→92px.
- **Gunsmith có MODEL SÚNG 3D XOAY** với đầy đủ phụ kiện đang lắp — port nguyên công thức render
  của bàn chế súng TACZ (modelview stack + renderStatic FIXED + flat lighting + hardware scissor):
  gắn giảm thanh là thấy giảm thanh trên nòng ngay.
- **Stat BAR kiểu refit TACZ**: 4 thanh DMG/RPM/MAG/HANDLING — nền + phần base + đoạn DELTA
  xanh/đỏ preview ngay khi rê chuột lên phụ kiện (đúng pattern GunPropertyDiagrams), kèm dòng
  modifier chi tiết bên dưới.
- **High Alert tùy chỉnh được**: effect JSON của perk giờ có `players` / `mobs` / `exemptTag` —
  tắt phát hiện mob nếu muốn thuần PvP, gắn tag scoreboard (vanilla `/tag`) cho trọng tài/staff
  để miễn trigger. Người mang Tracker vẫn tàng hình với High Alert như cũ. Mọi effect perk
  (attribute amount, amplifier, bonus map...) chỉnh trực tiếp trong tab PERKS của admin (field
  effects JSON) hoặc perks.json.

### v1.1.2 — "NBT AMMO" update
- **Đạn nằm TRONG NBT súng** (mặc định BẬT, tắt: `ammoInGunNbt` trong RULES): dùng đúng cơ chế
  dummy-ammo của TACZ — chỉ cần tag int `DummyAmmo` tồn tại là súng tự nạp từ chính nó (xác minh
  GunItemDataAccessor). Hết give cả đống đạn vào túi, hết bề mặt dupe đạn; Scavenger giờ bơm
  thẳng vào súng (kill = +đạn vào khẩu đang cầm, tick 30s = +30 viên mỗi khẩu dummy).
- **Stat súng update THEO PHỤ KIỆN**: DMG/HS/PIERCE/MAG giờ chạy qua `AttachmentDataUtils`
  (đúng utils refit của TACZ dùng) trên stack có gắn build thật — lắp mag mở rộng là MAG nhảy số
  ngay, cache theo hash build.
- **Pros/cons kiểu MW19**: phụ kiện hiển thị `+ RECOIL CONTROL` xanh / `- ADS SPEED` đỏ —
  KHÔNG số, đúng cảm giác card MW19 (đảo dấu cho recoil/inaccuracy/weight/ads).
- **Hover súng = tooltip thật**: rê chuột lên hàng súng hiện tooltip vanilla của stack (tên thật
  + lore pack + đạn).
- **Admin panel**: list CATALOG hiện icon item + TÊN THẬT của súng (DisplayNames); toggle đổi
  sang **slider pill ON/OFF** kiểu MW; **QUICK-ADD cho extra slot**: chọn slot extra → nhập item
  id (vd `superbwarfare:claymore_mine`) + số lượng → THÊM → entry vào catalog, slot tự bật, ô
  nhập còn nguyên để thêm tiếp.
- **Gamerule `mwloadoutEnabled`**: `/gamerule mwloadoutEnabled false` đóng băng toàn hệ thống
  (mở/lưu/trang bị đều từ chối kèm toast) — per-world, đổi giữa phiên được.
- Ghi chú model gunsmith: phụ kiện KHÔNG hiện trên model xoay được — TACZ chỉ mount phụ kiện
  trong pipeline geo first-person (refit thật giữ nguyên góc nhìn người chơi), model GUI/FIXED là
  LOD không có attachment bones. Stat bar + chấm type rail là nguồn sự thật của build; model để
  nhận diện súng.

### v1.1.3 hotfix — compile fixes + registry vào config
- Fix 2 patch v1.1.2 bị rơi (anchor lệch, replace thành no-op im lặng): field gamerule
  `RULE_LOADOUT_ENABLED` chưa từng được thêm vào MWLoadoutMod, và overload
  `buildGun(..., int dummyRounds)` chưa tồn tại dù thân hàm đã tham chiếu — cả hai đã vào đúng
  chỗ, có grep xác nhận từng symbol.
- **Toàn bộ dữ liệu admin chuyển về `config/mwloadout/`** (slots.json / catalog.json /
  perks.json / rules.json — mọi chỉnh sửa, ban list, ammo override): copy folder này lên
  dedicated server là toàn bộ setup đi theo, sống qua reset world như hồ sơ người chơi.
  World cũ tự MIGRATE một lần từ `world/data/mwloadout/` khi khởi động (thêm
  `rebindStorage()` — trỏ nơi lưu mà không xóa dữ liệu trong RAM, vì `load()` vốn clear + seed).
  `/mwloadout reload` cũng đọc từ config.

## v1.2.0 — RELEASE
Bản phát hành chính thức. Tổng hợp thay đổi so với 1.1.3:

**Gunsmith hoàn thiện theo MW19**
- **Model GEO thật thay vì LOD**: soi renderer TACZ thấy geo/LOD chọn theo KHOẢNG CÁCH camera —
  toạ độ màn hình bị coi là "xa" nên rơi vào LOD; `RenderDistance.markGuiRenderTimestamp()`
  (đúng cách bàn gunsmith table làm) ép geo model đầy đủ chi tiết trong GUI.
- **6 stat bar chuẩn MW19**: ACCURACY / DAMAGE / RANGE / FIRE RATE / MOBILITY / CONTROL —
  base từ chính GunData (inaccuracy STAND, damage qua refit utils, speed×life, RPM, weight CÓ
  phụ kiện qua AttachmentDataUtils, recoil pitch+yaw gộp); rê chuột lên phụ kiện là chạy lại
  NGUYÊN vector stat với phụ kiện đó lắp thử → delta xanh (tăng) / đỏ (giảm) đè lên bar, hiệu
  ứng chéo (scope nặng tụt MOBILITY) tự hiện đúng bar. Cache theo hash build.
- **Hệ điểm phụ kiện 100/20** (chỉnh trong RULES: attachmentPointLimit/Cost, 0 = tắt): đủ 5 phụ
  kiện → ô type còn TRỐNG khóa xám, bấm ứng viên mới bật cảnh báo "Hết điểm — gỡ một phụ kiện
  trước đã"; thay thế trong ô đã có vẫn tự do. Server cắt thừa ở mọi đường lưu (chống packet chế).
  Tab BẢN ĐỘ hiện số phụ kiện từng bản: "BẢN ĐỘ 2 (4)".

**Admin panel**
- **Fix nút đè nhau**: form dài (slot 14+ field) giờ CUỘN được — widget tái định vị thật theo
  scroll (hit-test luôn khớp), footer ghim, có thanh cuộn; bỏ hẳn quick-add gây rối.
- **Extra slot tự stock khi có mod**: scan thấy SuperbWarfare/DragonRise → EXTRA 1 nhận
  shield/repair_tool/ptkm_1r/edd, EXTRA 2 nhận c4_bomb/claymore_mine/tm_62, slot tự bật;
  không có mod = không thêm gì, EXTRA 3 luôn tắt mặc định cho admin tự nghịch.

**Khác**
- High Alert texture HD 256x (chevron glow mượt) thay 3 cục pixel.
- Registry + profile đều ở thư mục **`mwloadout/`** cạnh `config/` (xem mục "Thư mục dữ liệu" bên dưới) — deploy dedicated server bằng một lần copy.

### v1.2.1 — "SMOOTH" update
**Animation in/out toàn hệ GUI — hết flick**
- Mọi chuyển màn hình (loadout ↔ picker ↔ gunsmith ↔ perk) đi qua transition 120ms: veil tối
  fade-out rồi mới đổi màn, mở màn fade-in ngược lại — một lớp phủ duy nhất, không đụng vị trí
  widget nên hit-test luôn chuẩn, input khóa trong lúc chuyển.
- Đổi tab/category/bản độ: veil chớp nhanh 110ms trên vùng list che pop-in — cảm giác crossfade.

**Progression đầy đủ tùy chỉnh (vẫn TẮT mặc định)**
- RULES thêm nhóm XP riêng: `xpPerKill` (100) / `xpHeadshotKill` (200 — bắt qua chính
  EntityKillByGunEvent của TACZ, cộng phần CHÊNH so với kill thường nên không đếm đôi) /
  `xpPerLevel` (1000) / `xpMultiplier` (1.0 — sự kiện x2 XP đổi một field).
- Thanh LEVEL + XP hiện ở đáy rail màn hình loadout, CHỈ khi progression bật.

**Gunsmith QoL**
- **Bấm lại phụ kiện đang gắn = tháo** (đúng muscle memory MW); nút **GỠ HẾT** xóa cả bản độ;
  nút **LƯU BẢN ĐỘ** lưu tại chỗ không thoát (chớp "ĐÃ LƯU").
- **ESC = CONFIRM**: thoát kiểu gì build cũng được lưu — không bao giờ mất bản độ vì lỡ tay.
- **Tooltip ƯU/NHƯỢC tại con trỏ**: hover phụ kiện ra card riêng — tên + ƯU ĐIỂM xanh +
  NHƯỢC ĐIỂM đỏ, không số; panel phải giờ chỉ còn model + 6 bar (panel nới 132→172px,
  preview cao hơn, hàng ứng viên ngắn lại — tỉ lệ cân).
- Consumable mặc định 2 viên.

### v1.2.2 hotfix — kẹt màn hình sau khi vào gunsmith
Lỗi nghiêm trọng của hệ transition v1.2.1: khi màn hình con (gunsmith/picker) quay về,
Minecraft TÁI SỬ DỤNG instance LoadoutScreen — nhưng `closingAt` đang latch ở -1 từ lần chuyển
trước, mà guard của `transitionTo()` đòi `== 0` → mọi điều hướng từ đó (mở picker, gunsmith,
CLOSE, ESC) chết lặng vĩnh viễn = "kẹt luôn ở loadout screen". Fix: reset toàn bộ transition
state trong `init()` (hook chạy lại mỗi lần màn hình được hiện) + nới guard `<= 0`; tiện thể
re-arm `openedAt` nên mỗi lần quay về đều có fade-in nhẹ. Lỗi GL "Invalid mouse button 99" trong
log là noise GLFW/driver vô hại, không liên quan mod.

## v1.3.0 — "LOADED" update
**Recoil/CONTROL sửa tận gốc — đồng bộ đúng hệ modifier của TACZ**
- Nguyên nhân stat giảm giật "tàng hình": recoil trên phụ kiện KHÔNG phải Modifier thường mà là
  custom modifier lưu `Pair<Modifier, Modifier>` (pitch, yaw — fastutil) dưới key `"recoil"` —
  instanceof cũ bỏ qua sạch. Giờ: tooltip ƯU/NHƯỢC hiện `+ RECOIL CONTROL` / `- RECOIL CONTROL`
  đúng chiều, và thanh CONTROL tính lại giật pitch+yaw qua chính
  `AttachmentPropertyManager.eval` của TACZ cho TỪNG phụ kiện đã lắp — lắp nòng giảm giật là
  bar nhảy thật.

**Admin panel tự lưu**
- Gõ/bật tắt gì cũng tự lưu sau 700ms yên — không cần bấm LƯU; góc phải form có trạng thái
  "● CHƯA LƯU — đang tự lưu..." → "ĐÃ TỰ LƯU". Bản ghi MỚI (chưa có id chọn) không bao giờ tự
  lưu (tránh id gõ dở thành entry rác) — chỉ báo nhắc bấm LƯU.
- List KHÔNG nhảy về đầu sau mỗi thay đổi nữa — scroll giữ nguyên qua mọi resync; đổi tab mới
  reset.
- Field đạn của súng trong CATALOG đổi tên `maxCarryAmmo` — chỉnh trực tiếp đạn dự trữ
  (DummyAmmo NBT) của từng khẩu ngay tại đó.

**10 lớp sẵn khi vào lần đầu — không ai tay không**
- Hồ sơ mới tự có 10 CLASS: súng chính random THEO BỘ category (AR/SMG/LMG/SR/DMR/SG/BR rồi
  lặp — random ổn định theo UUID nên relog không xáo), phụ random pistol, dao, stim, frag
  `mw19:frag_gn`, flash `mw19:flash`, shield DragonRise + claymore SuperbWarfare (khi có mod),
  KHÔNG phụ kiện, perk mặc định **Double Time / Scavenger / Quick Fix**.
- Bộ ba mặc định (Quick Fix · Blood Rush · Scavenger) mỗi perk một cột. Layout hiện tại: P1 quick_fix·overkill·
  high_alert·ghost·tune_up·killchain / P2 hardline·double_time·sleight_of_hands·ninja·shrapnel / P3
  battle_hardened·scavenger·tracker·fragiles·fully_loaded; shield_up là perk Specialist cột 3.

**Tích hợp Killfeed (optional dependency thật sự)**
- Có mod killfeed → nút KILLFEED hiện góc trên phải màn hình loadout, mở panel cùng ngôn ngữ
  thiết kế (pill trượt, fade, hover grid) chỉnh live 8 toggle: feed/animation/nền/màu team/
  highlight kill/âm thanh/kill card art/emblem — ghi thẳng vào killfeed-client.toml qua chính
  máy móc config Forge. Toàn bộ qua reflection: không có mod = không có nút, đổi version lệch
  field = bỏ qua im lặng, không bao giờ crash.

## v1.4.0 — "CHANGE AND PLAY"
**Fix bug thật**
- **Consumable x2 mà nhận 1 — tìm ra thủ phạm**: không phải entry.count, mà slot `maxCount=1`
  mặc định đang cap `buildItem`. Defaults mới: consumable 4 / lethal 3 / tactical 3, và scan tự
  HEAL config cũ (nâng sàn tại chỗ, không đè chỉnh sửa admin cao hơn). Chỉnh số lượng trong
  CATALOG giờ ăn thật.
- **Lắp mag mở rộng = mag đầy ngay khi nhận**: gun phát ra ghi `GunCurrentAmmoCount` = băng đạn
  THẬT của bản độ (qua `AttachmentDataUtils`, tính cả extended mag) + `HasBulletInBarrel` — hết
  cảnh nhận Mag3 mà chỉ 30 viên phải bấm nạp.

**Change and play (MW19)**
- **Bấm class = trang bị luôn** — không còn nút equip thủ công; slot trống được server TỰ LẤP
  bằng item ngẫu nhiên hợp lệ (đi qua đúng validator của picker nên không bao giờ lấp đồ cấm),
  ghi vào class để player thấy mình quay được gì và đổi nếu muốn. Không ai deploy thiếu đồ.
- **Tự lưu khi quay về từ màn con** (picker/gunsmith/perk) — bỏ hẳn nghi lễ bấm SAVE.
- Picker: **sort A-Z theo tên đã resolve** (không phải key thô), **tự cuộn thẳng đến khẩu đang
  dùng** (chừa 1 hàng ngữ cảnh phía trên, tính đúng cả hàng cao thấp lẫn lộn).
- `lrtactical:flash_shield` tự seed vào melee khi có mod.

**UI**
- **PERK SELECT rework theo MW19** (đúng ảnh ref): 3 cột perk thành tab trên cùng — tab đeo icon
  perk đang trang bị của cột đó — list DỌC bên dưới, card mô tả bên phải (icon to + tên + mô
  tả), click perk đang mang lần nữa để gỡ, chuyển cột không cần thoát màn.
- **Kéo chuột xoay model** trong gunsmith — thả ra tự xoay về tư thế nằm ngang (decay mượt).
- **High Alert đổi sang pulse VIỀN MÀN HÌNH**: dải gradient mềm loé ở mép màn đúng hướng mối đe
  dọa — cảm giác thị giác ngoại biên thay vì sprite.
- **Animation chuyển màn chọn được qua config client** `transitionStyle`: fade (mặc định) /
  wipe (thanh quét ngang kiểu menu MW) / instant.
- Lang grounded MW19 hơn: Confirm / Equip / Deselect All / PERK SELECT...

**Ghi chú**
- Gamerule tắt/bật loadout để chơi survival đã có từ v1.1.3: `/gamerule mwloadoutEnabled false`.
- Hệ banner/emblem + kill banner tích hợp: khung sẽ vào ngay khi bạn gửi asset banner/emblem —
  thiết kế đã chốt (nút CUSTOMIZATIONS góc phải, favorite, random mỗi kill, random-favorites).

### v1.4.1 — audit hai đợt yêu cầu + fix compile
- Fix lỗi compile PerkPickerScreen: `PerkSlot.label` → `displayName` (field thật của record).
- **Cooldown đếm NGƯỢC realtime**: client lưu mốc hết hạn theo đồng hồ, GUI tính lại mỗi frame
  (`editSecondsLive()`) — số giây chạy sống thay vì snapshot tĩnh.
- **10 class là SÀN cho mọi người**: hồ sơ cũ (như 2 class của bạn) được PAD thêm cho đủ 10 khi
  load — class có sẵn không bị đụng, class mới nối tiếp đúng bộ bài category; trần maxPresets
  mặc định 20, config cũ đang 10 tự nâng lên 20 (giá trị admin đặt >10 giữ nguyên).
- **Tutorial 3 trang cho người mới** (mở loadout lần đầu): Loadout cơ bản → Gunsmith (điểm phụ
  kiện, kéo xoay model) → Perk & progression; SKIP được, flag lưu trong config client
  (`tutorialSeen=false` để xem lại).
- **Model 3D trong màn chọn súng** (đúng ảnh ref MW19): panel chi tiết bên phải hiện geo model
  của khẩu đang hover — khẩu ĐANG trang bị hiện kèm đầy đủ phụ kiện của bản độ hiện tại.

## v1.5.0 — "DEPLOY RULES" + Customizations + Regen
**Edit tự do / Equip theo difficulty (spec đầy đủ)**
- CHỈNH SỬA loadout giờ TỰ DO mọi lúc (kể cả trong combat) — chỉ TRANG BỊ (swap đồ thật) bị gate:
  PEACEFUL cooldown 10s không điều kiện; EASY 20s + không trong combat; NORMAL 30s HOẶC vừa hồi
  sinh + không combat; HARD chỉ khi chết (hồi sinh mới được swap). Creative/Spectator/OP miễn
  toàn bộ. Đứng trong ZONE AN TOÀN luôn swap được — hồi sinh/đổi gamemode có grace, rời zone là
  hết. Bấm class khi chưa đủ điều kiện: lựa chọn VẪN được lưu, toast nói rõ lý do + đếm giây,
  banner góc phải đếm ngược realtime ("Đổi loadout sau 12s" / "HARD: đổi khi hồi sinh").
- **Zone an toàn hiện hình**: vòng hạt END_ROD chạy quanh chu vi zone ở tầm mắt, 1s/lần — thấy
  radius bằng mắt. Lệnh: `/mwloadout zone add <radius>` (tại chỗ đứng, cần OP), `zone list`,
  `zone remove <index>`.

**Customizations (kill card) + Killfeed v1.3.0 đi kèm**
- Nút THẺ BÀI góc phải loadout mở thẳng picker kill card của killfeed (reflection, optional).
- Killfeed nhận 4 calling card + 13 emblem mới (sort đúng theo tỉ lệ: ngang 4:1 → card, vuông →
  emblem; gif >3MB được nén, 512px co về 280px), **3 chế độ**: CỐ ĐỊNH / NGẪU NHIÊN mỗi kill /
  NGẪU NHIÊN theo ★ (chuột phải card để đánh dấu ★) — server chốt card mỗi kill nên mọi client
  thấy cùng một thẻ.

**Regen merge (từ mod Regen riêng, giờ admin chỉnh live)**
- RULES mới: regenEnabled / regenDelaySeconds (3s) / regenPerSecond (2 HP) / regenDisableHunger —
  trúng đòn reset đồng hồ, đủ delay là hồi máu kiểu COD, saturation giữ no. Đồng bộ qua registry
  nên đổi trong admin panel ăn ngay không cần restart.

**QoL đợt chót**: nút ĐẶT LẠI CLASS (xóa sạch class hiện tại); class mới/trống tự gắn trio
Double Time/Scavenger/Quick Fix; model ngừng xoay khi đang kéo + lăn chuột ZOOM + tốc độ xoay
giảm nửa (16s/vòng); hàng súng rộng hơn; tooltip súng wrap đầy đủ xuống phần trống của panel;
nút "Deselect All" hết tràn chữ; REMOVE → "Tháo ra".

### v1.5.1 — context menu + Customizations MW-style
- **Fix nút đè nhau**: bỏ RESET CLASS ở footer (đè lên SAVE) — mọi thao tác class chuyển vào
  **menu chuột phải** trên hàng class: NGẪU NHIÊN (auto-fill hợp lệ) / ĐẶT LẠI (trio perk mặc
  định) / XÓA TRẮNG / YÊU THÍCH (đưa lên đầu) / XÓA CLASS (đỏ). Phím **DELETE** xóa class đang
  chọn. Tất cả server-authoritative qua packet mới (protocol 4).
- **Hết trùng số class**: sau mọi thao tác, tên dạng "CLASS n" tự đánh số lại 1..n theo thứ tự
  danh sách (5 cái CLASS 7 như ảnh không thể tồn tại nữa); tên player tự đặt không bị đụng.
- **Màn TÙY BIẾN kiểu MW** (chỉ hiện khi cài chung killfeed): thẻ bài + huy hiệu dạng lưới tile
  đúng ngôn ngữ mwloadout (hover, viền accent, fade, flash tab), preview gif SỐNG, chuột phải ★,
  pill chế độ CỐ ĐỊNH/NGẪU NHIÊN/NGẪU NHIÊN-★, ESC cũng lưu. Toàn bộ dữ liệu vẫn thuộc killfeed
  (reflection) — cài lẻ từng mod vẫn chạy bình thường.
- **Chống double-regen**: phát hiện mod Regen độc lập đang cài → tự TẮT regen tích hợp + log
  thông báo (gỡ mod cũ ra là regen tích hợp hoạt động).

### v1.5.2 — stat sync trọn bộ + spawn bubble + Shrapnel rework
- **ACCURACY & FIRE RATE đồng bộ phụ kiện thật**: inaccuracy là custom modifier dạng
  Map<InaccuracyType, Modifier>, rpm là Modifier đơn — cả hai giờ chạy qua
  AttachmentPropertyManager.eval trên TỪNG phụ kiện lắp (đúng họ với vụ recoil Pair). Bar
  ACCURACY/FIRE RATE nhảy theo build, dòng RPM cũng vậy; tooltip phụ kiện hiện "+ ACCURACY" /
  "+ FIRE RATE" đúng chiều.
- **DAMAGE hết bị DMR max bar**: đổi thang tuyến tính /15 sang đường cong sqrt(dmg/40) — AR ~7
  dmg giữa bar, DMR ~14 ở ~60%, chỉ pháo thật mới chạm nóc.
- **Spawn bubble**: trong bán kính `spawnZoneRadius` (mặc định 6, admin chỉnh 0-64) quanh điểm
  hồi sinh CỦA CHÍNH BẠN (giường/anchor, không có thì world spawn) — đổi loadout tự do, bước ra
  là luật difficulty áp lại. Spectator/Creative/OP vẫn miễn toàn bộ.
- **Shrapnel rework**: bỏ +1 lựu (vô hình khi mặc định đã 2) → **-60% sát thương nổ phải nhận**;
  lethal/tactical nâng trần mang lên 4 (config cũ tự heal).
- **High Alert dễ thấy**: dải viền dài gấp đôi (nửa cạnh màn hình) ôm sát mép + đường lõi sáng
  3px ngay rìa + gradient sâu hơn — bắt mắt ngoại biên đúng nghĩa.
- **Killcard QoL**: nút LƯU áp dụng NGAY mà KHÔNG thoát (chọn thẻ xong ở lại chọn huy hiệu, chớp
  ĐÃ LƯU), BACK riêng; **chuột phải** vào thẻ/huy hiệu ra menu: Trang bị / Tháo ra (về mặc định)
  / Yêu thích ★.

## v1.6.0
- **Shrapnel HOẠT ĐỘNG THẬT**: hai lỗ hổng — (1) config cũ giữ effect JSON đời trước không có
  key special "shrapnel" nên handler không thấy → scan tự HEAL perk trong config; (2) lựu mod
  ngoài (SuperbWarfare...) không gắn tag IS_EXPLOSION → nhận diện nổ mở rộng theo msgId
  (explos/mine/grenade/c4/bomb). Kháng 60% nổ giờ ăn với mọi nguồn.
- **Throwable deploy 4**: entry lethal/tactical auto-quản lý (lrt_/x_) count < 4 tự nâng lên 4 —
  entry admin tự tạo không đụng.
- **Fix nút GUNSMITH bị đè**: khôi phục khe 8px trước cột GUNSMITH (thí nghiệm -4px va chạm),
  hàng chọn súng ngắn lại đúng tỉ lệ tham chiếu.
- **6 stat bar ngay dưới súng trong panel chọn** (đúng ảnh ref MW19) — lấp luôn khoảng trống
  dưới panel, cache theo build.
- License MIT + credit: **Build with love and passion: L41nx, all thanks to Fable 5 <3**

### v1.6.1 — Shrapnel bản chốt + self-audit
- **Shrapnel hoàn chỉnh ba mảnh**: (1) kháng nổ nâng **80%** (mô tả ẨN số — chỉ ghi "Resistant
  to explosive damage"); (2) **+1 lethal VÀ +1 tactical** khi deploy (bình thường 2 → có perk 3);
  (3) **+1 deployable ở EXTRA 2** (claymore 1 → 2). Bonus cộng vào SỐ LƯỢNG (không chỉ nâng
  trần) qua buildItem mới, vẫn bị maxCount của slot chặn. Config cũ tự heal: count throwable về
  chuẩn 2 (perk cấp phần +1, không phải base), mô tả perk refresh, extra2 nâng trần 2.
- Self-audit: quét caller chữ ký cũ (0 sót), balance toàn bộ, lang parity, JSON hợp lệ; dọn
  asset chết (texture chevron high_alert cũ + field CHEVRON không dùng sau khi chuyển edge-pulse).

### Tương thích TACZ tương lai
- mods.toml khai báo `versionRange="[0,)"` — mod NẠP với mọi bản TACZ, không khóa version.
- Tầng API: mọi lời gọi đều qua API public ổn định của TACZ (TimelessAPI / IGun /
  AttachmentDataUtils / AttachmentPropertyManager) và các đường render/stat đều bọc try-catch —
  TACZ update MINOR gần như chắc chắn chạy tiếp; nếu TACZ đổi API BREAKING (hiếm, thường major)
  thì cần một lần recompile nhỏ, mod sẽ suy giảm nhẹ nhàng (mất stat/preview) chứ không crash.

## v1.7.0 — PERK BALANCE PASS + tacz_attributes (soft) + RANGE thật
**Tích hợp tacz_attributes — soft dependency đúng nghĩa**
- Mọi attribute tra cứu bằng ResourceLocation qua ForgeRegistries lúc RUNTIME — không compile
  dep, không khai báo bắt buộc. Thiếu mod: effect attribute bỏ qua êm + log cảnh báo một dòng,
  perk còn lại vẫn chạy. Có mod: ăn ngay.

**Perk retune (config cũ tự heal qua perkRevision, một lần duy nhất)**
- **Double Time**: giữ +10% tốc chạy, THÊM +40% tốc độ sneak — 1.20.1 chưa có attribute sneak
  (1.21 mới có) nên mod tự cài: đang crouch + có perk → modifier MULTIPLY_TOTAL +40% gắn động
  (UUID cố định, đứng dậy là gỡ, không dính vào save).
- **Quick Fix**: giữ hồi máu khi kill, THÊM mang 4 consumable thay vì 2 (bonus cộng vào số
  lượng deploy, trần consumable nâng 4).
- **Sleight Of Hand**: +40% tốc độ RELOAD (tacz_attributes:reload_speed) + **+40% tốc độ ĐỔI
  SÚNG** (tacz_attributes:draw_speed) — perk3 giờ có lựa chọn tốc độ thao tác đúng chất MW.
- **Scavenger**: nạp lại TOÀN BỘ utilities — consumable, lethal, tactical, extra1/extra2 (trước
  chỉ throwables) — trần nạp bám theo bonus perk (Shrapnel/Quick Fix) nên refill đúng số đã
  deploy. Vẫn giữ nửa kia: hồi đạn dummy khi nhặt xác.
- Cân bằng tổng thể (lý do): perk1 = di chuyển (Double Time) vs sống dai (Battle Hardened) vs
  đạn (Fully Loaded); perk2 = tiếp tế (Scavenger) vs thông tin (High Alert/Tracker) vs Overkill;
  perk3 = tốc tay (Sleight) vs hồi phục (Quick Fix) vs chống nổ (Shrapnel) — mỗi cột 3 hướng
  chơi rõ ràng, không perk nào trắng tay.

**RANGE bar hết ảo**
- Bar RANGE trước đo speed×lifetime = TẦM BAY TỐI ĐA — SMG đạn nhanh sống ngắn max bar giả.
  Giờ đo **effective range thật**: mốc damageAdjust đầu tiên (điểm damage bắt đầu rơi) trong
  bullet data, fallback 25m đúng mặc định TACZ, modifier effective_range của phụ kiện eval
  chồng lên — SMG ~30-50m nằm 1/3 bar, sniper 100m+ mới chạm nóc.

### v1.7.1 — comparison bars + weapon list QoL + fix icon perk
- **FIX icon perk lỗi** (shrapnel/quick_fix/sleight/double_time): bản retune v1.7.0 đoán sai tên
  file icon (double_time.png vs doubletime.png thật...) — trỏ về đúng file có thật
  (doubletime/quickfix/sledgeofhands, shrapnel dùng lethal.png), perkRevision 3 heal config một
  lần nữa. Hết FileNotFoundException spam log.
- **So sánh stat kiểu delta thay vì flick panel**: hover khẩu khác → 6 bar hiện giá trị khẩu
  hover, VẠCH TRẮNG đánh dấu mốc khẩu ĐANG TRANG BỊ, đoạn chênh tô XANH (hover hơn) hoặc ĐỎ mờ
  (hover kém) — nhìn một phát biết hơn kém từng chỉ số, panel không nhấp nháy đổi súng nữa.
- **Chuột phải vào súng trong list**: Trang bị (không thoát màn — chọn xong ở lại lượn tiếp),
  So sánh với khẩu này (ghim làm mốc baseline thay khẩu đang trang bị, kèm build đã lưu), Yêu
  thích ★ (lưu trong config client, ★ vàng trên hàng, TỰ NỔI LÊN ĐẦU danh sách, sort ổn định).
- **Nhận diện khẩu đang chọn**: viền kép accent + chip ✓ bên mép phải hàng.

### v1.7.2 — HOTFIX crash đổi loadout
- **Fix ConcurrentModificationException** khi mở màn chọn súng (crash ngay lúc đổi loadout):
  dòng sort favorites ở v1.7.1 bị chèn nhầm VÀO GIỮA lambda của comparator sort cũ, tạo hai
  lần sort chồng chéo phá vỡ cấu trúc. Giờ gộp làm MỘT comparator duy nhất: đang trang bị → ★
  yêu thích → A-Z. Một lần sort, không CME.

### v1.7.3 — QoL panel & compare
- **Tooltip hover súng giờ TẮT mặc định** (đỡ vướng list) — bật lại qua client config
  `showHoverTooltip=true`. Panel thông số bên phải vốn đã hiện đủ nên tooltip nổi là thừa.
- **Compare rõ ràng**: panel hiện dòng "so với <TÊN KHẨU>" màu accent ngay trên phần tên súng —
  biết chính xác đang so delta với khẩu nào (khẩu ghim qua chuột phải, hoặc khẩu đang trang bị).
- **Panel info + ô GUNSMITH rộng hơn ~13%** (128→146 / 56→64px): dễ đọc thông số & tên súng dài;
  spacing tự tính lại nên khe 6px trước GUNSMITH giữ nguyên, không đè nút.

### v1.7.4 — Fragiles thêm hiệu ứng Wither
- **Fragiles** ngoài Strength IV giờ còn: mỗi đòn BẠN đánh trúng địch sẽ gây **Wither 0 trong
  2s**. Mục đích không phải sát thương DoT (rất nhỏ) mà là **chặn hồi máu tự nhiên** — địch bị
  bạn tag không thể hồi máu trong 2 giây đó (đè cả regen của mod này lẫn regen vanilla). Hạt
  hiệu ứng để chế độ ẩn nên không rối màn hình đối phương. Config cũ tự nhận qua perkRevision 4.

### v1.7.5 — HOTFIX compile
- Fix lỗi biến `current` khai báo trùng trong `renderStatsPanel` (khối preview v1.7.1 và khối
  "compare to" v1.7.3 dùng cùng tên) — đổi biến ở khối compare thành `equippedSel`.

## v1.8.0 — QoL trang bị & lưu
- **Không còn auto-equip khi bấm chọn**: bấm 1 lần chỉ CHỌN class (xem/sửa); **double-click**
  hoặc **chuột phải → Trang bị** mới thực sự deploy. Hết cảnh lỡ tay đổi loadout khi lướt rail.
- **Bỏ nút SAVE**: mọi thay đổi tự lưu khi rời màn con (picker/gunsmith/perk) và khi đóng —
  không cần nhấn nút. Rail gọn hơn.
- **Tên class KHÔNG trùng nhau — vá tận gốc ở server**: mỗi lần save, tên bị dedupe (so
  không phân biệt hoa thường); trùng thì tự thêm hậu tố " 2", " 3"… Hết mớ "CLASS 1" khi bấm
  save nhiều lần.
- **HUD cập nhật REALTIME sau randomize/reset/clear/pin**: onDataRefreshed giờ nạp lại working
  copy từ profile mới nên slot card + HUD đổi ngay trong frame, không cần ra vào lại (giữ nguyên
  ô tên nếu đang gõ dở).
- **Lang grounded hơn**: mô tả perk viết lại giọng quân sự thực tế (vd Double Time "hold angles
  quietly", Fragiles "poison the wound (Wither)…"), menu class đổi sang động từ rõ (Randomize/
  Reset to default/Strip everything/Pin to top/Delete class), "Remove" → "Unequip"/"Tháo ra".

### v1.8.1 — fix dupe tên loadout + debug mode
- **FIX tận gốc "Sigma 2 2 2"**: mỗi lần equip, client auto-save lại class → dedupe cũ thấy tên
  đã có suffix của chính nó và cứ nối thêm số. Sửa hai tầng: (1) dedupe giờ IDEMPOTENT — tên
  không đụng class khác thì giữ nguyên, khi đụng thì bóc suffix cũ ra khỏi base trước rồi mới
  đánh số ("Sigma 2 2" → base "Sigma"); (2) client **dirty-tracking**: chỉ auto-save khi
  working copy THỰC SỰ đổi so với lần lưu trước, nên equip/đóng/mở không còn re-save thừa.
- **Dọn profile đã hỏng**: khi load, tên bị chồng số ("A 2 2 2") được thu gọn về base một lần
  rồi re-dedupe sạch — profile của bạn tự sửa khi vào lại.
- **Chế độ DEBUG** (client config `debugMode=true`): overlay góc trái màn loadout hiện equip
  verdict (YES/NO), lý do, timer cooldown/combat sống, index + dirty index, số class — test luật
  difficulty/cooldown không cần đoán. (Nhắc lại: trong thế giới đơn bạn là OP nên được miễn luật;
  `/deop` chính mình để thấy luật chạy, hoặc test trên server.)
- Auto-save giờ flush đúng lúc chuyển class / đóng màn nên đổi loadout không mất chỉnh sửa.

### v1.8.2 — HOTFIX compile
- Fix `@Override` lạc chỗ: khi chèn `renderDebugOverlay` (v1.8.1), annotation `@Override` của
  `onClose` bị kẹt lên trên method private mới (không override gì) → lỗi compile. Trả `@Override`
  về đúng `onClose`.

## v1.9.0 — Admin quick-add, gỡ regen, config gọn
- **THÊM NHANH VẬT PHẨM trong admin (tab CATALOG)**: ô nhập id + ô slot + nút ADD (hoặc Enter).
  Gõ `minecraft:pufferfish` + `extra3` → Enter: mod kiểm tra id trong registry, tạo entry ITEM
  ngay, ô tự clear + chớp "✓ + Pufferfish → extra3", sẵn sàng item kế. Không cần gõ Java/JSON,
  không cần cầm sẵn trên tay (nút "from hand" cũ vẫn còn cho ai thích).
- **Extra 3 = slot opt-in**: không bắt buộc, KHÔNG bị random-fill — chỉ hiện món player chủ động
  vào pick (đúng cho đồ novelty như cá nóc). Config cũ tự heal.
- **Class mới mặc định tên "Custom Class"** (không số); trùng thì dedupe thành "Custom Class 2"…
- **Gỡ bỏ hoàn toàn cơ chế regen** khỏi mod (server tick, Rules, admin panel) — modpack tự lo
  phần hồi máu. Không còn field/toggle regen ở đâu.
- **Config tổ chức lại rõ ràng**: chia nhóm [theme] (màu), [display] (HUD/transition/tooltip),
  [behavior] (tutorial/favorites), [advanced] (debug) — mỗi mục có comment mô tả đầy đủ, dễ đọc
  dễ chỉnh hơn hẳn.

### v1.9.1 — Gunsmith stat baseline + panel widen + fix scroll đè + layout style
- **Vạch mốc súng gốc trên thanh stat gunsmith**: mỗi bar giờ có vạch sáng đánh dấu vị trí của
  SÚNG GỐC (không phụ kiện) làm cột mốc cố định. Lắp phụ kiện: đoạn tăng tô XANH, giảm tô ĐỎ,
  so ngay với vạch gốc — nhìn phát biết phụ kiện làm tăng/giảm gì. Có chú thích "| = súng gốc".
- **Panel súng + stat rộng hơn** (gunsmith 172→220px, picker 146→188px); vùng list phụ kiện thu
  gọn lại nên không chiếm hết HUD.
- **Fix scroll đè nút loadout**: rail class giờ được scissor-clip dừng trên nút footer, kèm fade
  mềm ở mép trên/dưới khi cuộn — hết cảnh class 12 đè lên "+ NEW CLASS"/EQUIP.
- **Hai kiểu layout gunsmith** (config `display.gunsmithLayout`): `compact` (mặc định, list giữa
  + panel phải) hoặc `cinematic` (kiểu MW19: model súng LỚN ở giữa sân khấu, thanh stat góc
  dưới-trái, list phụ kiện thu về cột trái) — chọn theo sở thích, tham chiếu bố cục MW19 trong
  ảnh bạn gửi.

### v1.9.2 — fix bar tràn + màn Settings bấm-chọn
- **Fix thanh stat tràn khỏi ô** (layout cinematic): barWidth trước dùng cứng STATS_WIDTH nên
  vượt panel; giờ drawStatBars nhận width riêng, panel stat cinematic cao 128px chứa đủ 6 bar +
  chú thích, có scissor-clip chặn mọi tràn. List phụ kiện dừng trên panel (height-150) nên không
  chồng lên stat nữa.
- **MÀN SETTINGS trong game — bấm chọn, KHÔNG gõ**: nút "Settings" góc phải loadout mở màn cài
  đặt; mỗi dòng là một pill bấm để đổi giá trị (bố cục gunsmith compact/cinematic, hiệu ứng
  chuyển màn fade/wipe/instant, hình bóng súng, tooltip hover, debug). Không cần tìm hay điền
  vào file .toml nữa — bấm là đổi, thoát là lưu.

### v1.9.3 — Tách ADD ITEM thành tab riêng + pufferfish mặc định
- **ADD ITEM là TAB RIÊNG** (SLOTS / CATALOG / PERKS / RULES / **ADD**): hết đè lên thanh tìm
  kiếm của CATALOG. Sang tab ADD mới thấy form thêm vật phẩm — ô ID + ô slot đóng khung rõ ràng
  + nút ADD (Enter cũng được), kèm tiêu đề/nhãn/hint. Tab CATALOG giữ nguyên search để duyệt &
  chỉnh súng bình thường. Bấm một hàng trong list ở tab ADD sẽ copy gameId của nó vào ô để
  clone/tinh chỉnh nhanh.
- **Pufferfish mặc định trong Extra 3**: seed sẵn entry cá nóc (id `pufferfish`, `minecraft:
  pufferfish`) vào slot extra3 — vì extra3 là opt-in nên nó KHÔNG tự fill, chỉ hiện khi player
  chủ động vào Extra 3 chọn. Config cũ tự thêm khi load.

### v1.9.4 — fix nút ADD đè tab + reposition stat box cinematic
- **Fix nút ADD đè lên tab SLOTS/CATALOG/PERKS/RULES**: nút ADD trước là widget luôn hiển thị
  (chỉ ẩn hai ô nhập), giờ ẩn/hiện cùng cả cụm — chỉ xuất hiện ở tab ADD. Các tab khác sạch sẽ.
- **Stat box cinematic dời ra GIỮA dưới model**: trước nó nằm góc dưới-trái đè list phụ kiện +
  chừa khoảng trống giữa màn; giờ căn giữa ngay dưới súng (panel 300×120, tự canh giữa theo stage),
  model dùng trọn nửa trên, list phụ kiện chạy gần full chiều cao. Hết chỗ trống ngắc + hết đè.

## v2.0.0 — Bản hoàn thiện: nền trong suốt, animation mượt, đồng bộ toàn mod
**Nền menu điều chỉnh được + trong suốt**
- Nền menu KHÔNG còn đen xì đặc. Thêm tùy chọn `display.backdropOpacity` (0-100, mặc định 72) —
  hạ xuống để thấy game phía sau, chỉnh ngay trong màn SETTINGS bằng nút bấm (0/20/40/60/72/85/
  100%). Nền cũng fade-in theo screen thay vì bật đen tức thì. Áp cho cả menu người chơi lẫn
  admin panel.

**Animation mượt toàn cục (học từ mod tham chiếu bạn gửi)**
- Bộ công cụ easing frame-rate-independent trong MWStyle: `approach()` dùng công thức
  lerp/pow nên 30fps hay 240fps đều animate cùng tốc độ thực; thêm easeOutCubic / easeOutBack /
  easeInOutCubic.
- **Cuộn mượt (smooth scroll)** áp cho MỌI list: rail class + slot cards (loadout), danh sách
  phụ kiện (gunsmith), danh sách vũ khí (picker), list admin — con lăn đặt "đích", giá trị hiện
  hành trượt mượt tới đích mỗi frame thay vì nhảy giật.
- Hover các nút giờ ease vào/ra mượt và ổn định theo đồng hồ thực (không còn phụ thuộc FPS).

**Kỹ thuật & dọn dẹp**
- Backdrop opacity + easing tập trung ở MWStyle nên mọi màn dùng chung một chuẩn — nhất quán,
  dễ bảo trì.
- Đây là mốc gom mọi cải tiến tích lũy: perk cân bằng (Double Time sneak, Quick Fix supplies,
  Sleight draw/reload, Scavenger toàn utility, Shrapnel 80% + equipment, Fragiles Wither),
  stat sync đúng (accuracy/rpm/range effective + vạch mốc súng gốc), admin ADD tab + quick-add,
  tên class dedupe idempotent, debug overlay, hai layout gunsmith, regen gỡ bỏ cho modpack lo.

### v2.1.0 — slider, ẩn HUD, âm thanh mở, ổn định
- **Opacity nền = SLIDER kéo**: thay nút bấm sẵn bằng thanh trượt thật trong màn Settings — kéo
  0→100% thấy giá trị đổi tức thì, mượt.
- **Ẩn HUD game khi mở loadout**: vào bất kỳ màn nào của mod (loadout/gunsmith/picker/perk/
  settings/cards/admin) sẽ tự bật hideGui (cơ chế F1 của Minecraft) — HUD trong game biến mất
  cho gọn + đỡ tốn render, thoát ra tự khôi phục đúng trạng thái F1 bạn đang để. Quản lý tập
  trung ở client tick nên chuyển qua lại giữa các màn con không nhấp nháy.
- **Âm thanh mở UI**: mở loadout phát tiếng chime hai nốt (dựng từ sound click chuẩn của game,
  không rủi ro thiếu sound).
- Ổn định: playOpen tránh field sound không chắc tồn tại; slider/hide-HUD/scroll đều dùng chung
  MWStyle nên nhất quán, không đụng gameplay.

### v2.1.1 — flowing fill hover + fix ẩn HUD
- **Hover kiểu MW19 (mặc định)**: thay lưới grid bằng vệt xanh biển nhẹ CHẢY ĐẦY ô từ trái sang,
  tăng tốc dần (easeOutCubic) với mép dẫn sáng hơn — đúng cảm giác lướt chọn trong MW. Đổi được
  trong Settings (`fill` / `grid`) hoặc config `display.hoverStyle`.
- **FIX HUD không ẩn**: cơ chế hideGui theo tick có độ trễ/không phủ hết HUD của mod khác. Giờ
  hủy hẳn RenderGuiEvent.Pre khi màn của mod đang mở — chặn TOÀN BỘ lớp HUD mỗi frame, không còn
  độ trễ frame, không sót HUD nào. Vẫn giữ hideGui làm lớp phụ, khôi phục trạng thái F1 khi thoát.

### v2.1.2 — perk không bỏ trống, L toggle, fade & equip
- **Perk slot không bao giờ trống**: mỗi cột perk luôn có perk — chỉ ĐỔI được, không tháo ra
  blank. applyDefaultPerks giờ vá TỪNG cột trống (không chỉ preset rỗng hoàn toàn), fallback về
  perk đầu tiên hợp lệ của cột nếu thiếu trio mặc định.
- **Bấm L lần 2 đóng loadout**: L giờ toggle — mở nếu chưa mở, đóng nếu đang ở màn loadout.
- **Nút EQUIP ở góc dưới-trái** (dưới rail class) — xác nhận đúng vị trí.
- **Fade mép danh sách class card**: cuộn cards giờ có fade mềm trên/dưới (khớp với fade của
  rail) thay vì cắt cứng; hover ô dùng flow-fill xanh gọn trong ô, hết vệt kéo xuống.

## v2.2.0 — Rework logic loadout + ammo balance + 3 perk mới (phần 1 của bản streak)
**Rework gating loadout (học từ MW):**
- Spectator LUÔN miễn. Gamerule mới `restrictOpAndCreative` (admin panel): bật để OP + Creative
  CŨNG phải theo luật loadout — test đúng như người chơi thường trải nghiệm.
- Gamerule mới `requireZoneToEquip`: chỉ LOAD được loadout khi ở trong zone/spawn radius. Chọn
  ngoài zone thì thay đổi được NHỚ và tự áp ở lần hồi sinh sau (mô hình MW "đổi giờ, deploy mạng
  sau"). Toast báo rõ "phải ở trong vùng".
- Chỉnh sửa loadout LUÔN tự do; chỉ việc LOAD/đổi bị gate — đúng yêu cầu tách bạch edit vs equip.
**Ammo balance (README):** AR 120, SMG 120, LMG 200, BR 100, DMR 80, SG 50, SR 25, pistol 60,
launcher 2. Perk Fully Loaded giờ +60% (nhân), launcher ngoại lệ không cộng.
**3 perk mới:** Killchain (giữ streak qua 1 lần chết), Hardline (streak rẻ hơn 1 kill), Ghost
(miễn Glowing + tàng hình trước High Alert). Tracker nerf: chỉ glow khi đang gây damage. Perk mặc
định đổi thành Double Time / Hardline / Quick Fix.
(Kill streak + specialist system + UI MW19 sẽ ở cycle tiếp theo.)

## v2.3.0 — Kill streak + Specialist system (server-side core)
**Kill streak (mặc định):**
- Ladder mặc định: 3 kill → nmw2:bomb_drone, 5 → nmw2:spotter_scope, 7 → nmw2:laserpointer
  (chỉnh được, sẽ có UI admin ở cycle sau). Đạt mốc → reward được QUEUE, bấm G để gọi vào túi đồ;
  chờ trong hàng đợi đến khi bấm nhận.
- Item killstreak nạp với đúng 1 viên (GunCurrentAmmoCount:1). Bắn hết → tự động clear khỏi túi
  (tick check mỗi 5 tick). Đổi loadout khi còn đạn thì KHÔNG bị xóa (whitelist NBT tag).
**Specialist:**
- Bật (per-player) → không nhận item nữa; thay vào đó chọn 3 perk. Kill 3/4/5 lần lượt tự kích
  hoạt từng perk đã xếp, kill 6 mở khóa TOÀN BỘ. Chết → reset cả chuỗi lẫn perk.
- Perk specialist phải thuộc pool + KHÔNG trùng perk đang trang bị (validate server-side).
**Perk tương tác:**
- Hardline: mọi mốc streak rẻ hơn 1 kill (cả item lẫn specialist).
- Killchain: lần chết ĐẦU của mạng không reset streak; lần chết thứ hai mới reset.
- Thông báo qua toast + âm thanh Minecraft khi nhận streak/perk.
(UI HUD streak MW19 + màn chọn specialist + admin streak editor + High Alert rework = cycle sau.)

## v2.4.0 — Kill streak + Specialist UI (MW19)
- **HUD streak MW19** (góc dưới-trái): thanh progress đầy dần tới mốc reward tiếp theo, hiện tên
  item + số kill cần; khi có reward chờ thì báo "KILLSTREAK READY [G] xN". Specialist mode: 3 pip
  perk sáng dần ở kill 3/4/5 + pip "ALL" vàng ở kill 6. Vẽ bằng render-engine, pop nhẹ mỗi kill.
- **Màn Specialist** (nút Specialist trong loadout): toggle chế độ Item-killstreak ↔ Specialist;
  khi Specialist chọn 3 perk theo thứ tự từ pool, ẩn các perk đang trang bị + perk đã chọn ở ô
  khác (không trùng). Server re-validate khi lưu.
- **Đồng bộ streak realtime**: packet S2CStreakState riêng cho state transient (streak/queue/
  specialist stage), sync khi kill/chết/hồi sinh nên HUD luôn đúng.
(Còn lại: admin streak editor UI + High Alert rework viền mượt — cycle sau.)

## v2.5.0 — High Alert rework + Admin streak editor
- **High Alert viền mượt seamless**: thay vệt cạnh cắt cứng bằng glow bám TOÀN cạnh, alpha đạt
  đỉnh về hướng kẻ địch rồi ease dần về 0 dọc cạnh (24 lát gradient/cạnh) — đọc như tầm nhìn ngoại
  vi mềm mại, không còn đường nối lộ. Threat chéo góc thì hai cạnh cùng sáng theo tỉ trọng hướng.
- **Admin streak editor** (tab RULES): sửa ladder killstreak ngay trong panel dạng "kills=itemId"
  mỗi dòng (vd `3=nmw2:bomb_drone`), pool perk Specialist (danh sách id ngăn cách dấu phẩy), và
  toggle bật/tắt killstreak. Lưu là áp ngay — thêm/đổi item streak không cần sửa file.

## v2.6.0 — Fix chức năng streak/perk + rework UI (comfortable, học GD656)
**Fix bug (2.5.0 test):**
- **Streak giờ tính MỌI kill** (mob + player), không chỉ PvP — trước đây giết mob không lên streak
  nên reward/perk không bao giờ kích hoạt dù counter chạy.
- **Specialist perk giờ thực sự có tác dụng**: `playerHasSpecial` đọc CẢ perk được specialist cấp
  runtime (Ghost/Hardline/... trước chỉ detect perk trong loadout slot). Thêm set runtime, dọn
  khi chết/reset.
- Specialist threshold sửa đúng ref MW: **2 / 4 / 6 kill** (không phải 3/4/5), 6-kill = mở toàn bộ.
**UI:**
- **Bỏ HUD streak góc dưới** (killfeed lo rồi, không hiện nữa cho đỡ rối).
- **Specialist gộp vào TRANG PERK** (đúng ref): cạnh 3 cột perk là 3 tab 2/4/6 KILL + nút toggle
  Activate Specialist; chọn perk cho từng mốc ngay tại đây, ẩn perk đã trang bị/đã chọn.
- **Trang Killstreak Weapons riêng** kiểu gun-selection (care-package): ladder ngang theo mốc kill
  + cột chi tiết bên phải. Nút "Killstreak" trong loadout.
- **Animation rework học GD656**: bỏ hẳn instant/wipe (đau mắt) → fade smoothstep; nút giờ FADE +
  trượt lên khi mở màn, hover thì FLOAT nhẹ + underline accent mọc dần; flash đổi tab dịu lại.
- **Nền frosted nhiều lớp** (dim + gradient depth + vignette) thay opacity phẳng. (Gaussian blur
  thật cần post-shader không có sẵn ở 1.20.1 — có thể thêm bản sau nếu muốn.)
**High Alert fix**: mớ khối vuông chồng là do 24 lát gradient stack. Viết lại kiểu edge-of-screen:
mỗi cạnh 1 gradient mượt fade vào trong (trái/phải dùng cột 1px sub-pixel), alpha theo tỉ trọng
hướng — liền mạch, hết khối vuông. Không dùng texture, thuần render-engine.

## v2.6.1 — Fix compile
- Bỏ định nghĩa `easeOutCubic` trùng trong MWStyle (đã có sẵn bản gốc có clamp) — sửa lỗi
  "method already defined" khi build.

## v2.6.2 — Fix config cũ không có streak/specialist data
**Nguyên nhân**: `streakRewards` + `specialistPerkPool` chỉ được seed khi tạo config LẦN ĐẦU. Config
của bạn tạo từ trước v2.3.0 nên hai list này RỖNG → tab specialist 2/4/6 không có perk để chọn,
trang Killstreak Weapons báo "No rewards configured".
**Fix**: thêm `streakRevision` + `healStreakDefaults` (giống cơ chế perkRevision) — khi load config
cũ mà list rỗng thì tự seed ladder mặc định (3/5/7) + pool 8 perk specialist, save lại, rồi sync
về client. Admin đã tự set ladder riêng thì KHÔNG bị đè (chỉ seed khi rỗng).
Sau khi cập nhật, vào lại server/world một lần để heal chạy — tab specialist sẽ có perk, trang
killstreak sẽ có item.

## v2.7.0 — Fix batch: High Alert dịu, LOS, specialist never-empty, chat notify, Field Upgrade, admin slider
- **High Alert dịu hẳn**: bỏ sin-pulse liên tục + không stack nhiều lớp. Giờ gom 1 trọng số/cạnh,
  nháy 1 lần rồi fade (envelope rise ~110ms → decay), opacity mỏng (0.22), coverage nhỏ (depth 26).
  Hết đau mắt/gây lú.
- **Fix xuyên tường**: High Alert giờ raycast ClipContext watcher→holder; có block chắn thì không
  kích hoạt.
- **Specialist never-empty**: bật Specialist tự fill 3 ô bằng perk ngẫu nhiên KHÔNG trùng (không
  trùng perk chính + không trùng ô specialist khác); đổi được như perk chính nhưng KHÔNG để trống.
- **Notify lên CHAT** thay vì toast: nhận perk/killstreak báo ở chat (kèm tên món đã format đẹp),
  không che tâm ngắm.
- **Đổi tên slot**: EXTRA 1/2/3 → **FIELD UPGRADE 1/2/3** (+ heal cho config cũ).
- **Admin RULES slider**: bật/tắt **killstreak** và **specialist**; tắt specialist thì strip
  specialist trong trang perk ẩn theo, tắt killstreak thì nút Killstreak trong loadout ẩn theo.

## v2.8.0 — Killstreak UI rework: weapon-selection thứ 2 (model 3D + admin editor)
Màn Killstreak Weapons làm lại hoàn toàn theo mẫu primary weapon selection, nhưng bố cục NGANG:
- **Model 3D ở giữa**: dùng chính GunPreview của gunsmith — item killstreak đang chọn quay 3D,
  kéo xoay + cuộn zoom được, geo model đầy đủ (markGuiRenderTimestamp như bàn gunsmith).
- **Ladder NGANG tối đa 5 ô** dưới model, mỗi ô gắn mốc kill + tên item; bấm ô để đưa item lên
  sân khấu 3D.
- **Admin mode** (mở từ nút "UI Killstreak" trong tab RULES của admin panel):
  - Tự **scan toàn bộ súng TACZ dạng RPG (launcher) + pistol** từ catalog thành danh sách chọn.
  - Bấm súng để gán vào ô đang chọn; nút +/- chỉnh số kill từng ô; +Ô / -Ô thêm bớt (cap 5).
  - SAVE gửi cả ladder về server (C2SAdminUpdate RULES) — thêm/bớt vũ khí + set kill ngay trong UI.
- **Player mode** (mở từ loadout): hiển thị ladder + model 3D read-only, như bạn yêu cầu "mặc định
  hiện tại chỉ hiển thị, chưa cần chỉnh sửa".

## v2.9.0 — Loadout layout: cột Killstreak cạnh loadout (MW4)
- **Cột Killstreak dọc** đặt bên PHẢI khối card loadout (cạnh weapon/lethal/tactical/field upgrade
  + perks), đúng bố cục MW4 "killstreak hiển thị cạnh loadout". Mỗi tier là 1 tile: mốc kill + tên
  item, tối đa 5. Bấm mở trang Killstreak Weapons (model 3D).
- Bỏ nút "Killstreak" top-right thừa (cột thay thế). Ẩn cả cột khi admin tắt killstreak.
- Content column giữ nguyên, cột killstreak thêm ở vùng phải nên không xô lệch card cũ.

## v2.9.1 — Fix compile
- Thêm import `SlotDefinition` thiếu trong CreativeTabScanner (healFieldUpgradeNames) — sửa lỗi
  "cannot find symbol: class SlotDefinition".

## v2.10.0 — Killstreak: item id fix, tier 9, card dưới perks, scale UI
- **Sửa item id**: nmw2:laserpointer → **nmw2:laser_pointer**.
- **Thêm tier 9 kill = ts:fl10** → ladder mặc định giờ 3/5/7/9 (4 mốc). Migration cho config cũ
  (gate streakRevision=2): sửa typo id + thêm tier 9 nếu ladder còn là default 3 mốc.
- **Killstreak thành 1 CARD ngay dưới perks** trong loadout (bỏ cột bên). Card hiện các mốc kill
  mini; bấm mở trang Killstreak Weapons (4 ô item). Ẩn khi admin tắt killstreak.
- **Scale UI killstreak to hơn**: sân khấu model 320→420 rộng / 150→200 cao, ô tier 40→54 cao &
  rộng hơn, model preview scale 46→60 — cân đối với GUI hơn.

## v2.11.0 — Fix batch lớn: logic streak/specialist, exploit, UI, config
**Chức năng:**
- Killstreak item giờ AUTO-GIVE thẳng vào túi ngay khi đạt mốc (bỏ queue chờ G). Killstreak là mặc
  định cho mọi player đến khi bật Specialist.
- Specialist: 3 perk ở 2/4/6 kill, MỞ TOÀN BỘ perk chỉ ở KILL 8 (không phải 6).
- Killchain/Hardline chỉ dịch mốc ITEM killstreak — KHÔNG ảnh hưởng Specialist.
- **Fix exploit**: gamerule `mwloadoutEnabled` tắt thì KHÔNG restock respawn, KHÔNG Scavenger refill,
  KHÔNG tính streak/kill-hook nữa (trước vẫn refill lậu).
**UI:**
- Bỏ 2 đường fade góc màn hình (vignette band lỗi trong frostedBackdrop).
- Bỏ hiệu ứng grid hover — chỉ còn wash fade dịu (dissolve). Xóa flowFill/gridHover.
- Killstreak trong loadout giờ là 1 NÚT dưới perks (bỏ per-slot box); KHÓA khi Specialist bật +
  báo message rõ ràng (killstreak & specialist loại trừ nhau).
- High Alert: intensity + màu config được (`highAlertIntensity` 0-100, `highAlertColor`), đậm hơn
  được để dễ nhận diện.
**Content:**
- Bỏ repair_tool khỏi Field Upgrade (+ tự gỡ khỏi config cũ).
- Item id: nmw2:laserpointer → nmw2:laser_pointer; ladder 3/5/7/9; ô nhập item id trong admin UI.

## v2.12.0 — Blur nền thật (kiểu blur-forge) + button GD656 verified
- **Gaussian blur nền thật**: ship post-shader `shaders/post/mw_blur.json` (2-pass H/V) + program
  GLSL, chạy PostChain trên framebuffer trước khi vẽ panel — nền game MỜ đi (không chỉ dim). Kỹ
  thuật giống mod Blur/blur-forge. Có guard toàn diện: lỗi shader/driver thì tự tắt + fallback về
  frosted dim, không bao giờ crash. Config `blurEnabled` bật/tắt; độ tối nền vẫn theo slider
  `backdropOpacity`. (Radius mờ = 6, sửa trong json nếu muốn.)
- **Button overhaul**: đã audit — MỌI nút UI dùng MWStyle.flatButton với animation GD656 (fade +
  trượt vào khi mở, hover float + underline accent mọc). Không còn nút thô.

## v3.0.0 — Self-audit lớn: exploit, sync, perk config đầy đủ
**Fix exploit / bug thật:**
- **High Alert chỉ nháy cho OP**: `highAlertScan` dùng thẳng `LoadoutApplier.hasSpecialPerk(preset)`
  nên bỏ sót perk cấp qua Specialist auto-unlock (chỉ nằm trong `activeSpecialistPerks`, không nằm
  trong preset). Đổi sang `PerkEffects.playerHasSpecial()` (đã merge sẵn 2 nguồn) — bug tương tự
  cũng dính Shrapnel (kháng nổ) và Scavenger (resupply/kill), đã sửa cả 2.
- **Buff Specialist tồn tại vĩnh viễn**: tắt Specialist (hoặc đổi 3 perk chọn) đúng lúc đang giữ 1
  perk đã unlock (VD Shield Up = Absorption, Battle Hardened = +20 giáp) rồi chết → buff đó KHÔNG
  BAO GIỜ bị gỡ nữa vì `onRespawn` chỉ dọn `if (specialistActive)`. Giờ luôn dọn khi respawn, đúng
  tinh thần "life-scoped" — không thể giữ buff qua nhiều mạng nữa.
- **Gamerule tắt mod chưa triệt để**: fix ở v2.11.0 mới che streak/restock/scavenger, còn Shrapnel,
  Double Time (sneak boost), Tracker, Fragiles-Wither, High Alert vẫn hoạt động dù tắt gamerule.
  Giờ cả 8/8 chỗ đều check.
- **Streak reward bị "nhảy cóc"**: `advanceItemStreaks` so `==` thay vì `>=`; nếu Hardline đổi
  trạng thái đúng lúc giữa 2 kill, ngưỡng tính lại có thể nhảy qua currentStreak → mất thưởng vĩnh
  viễn. Đổi sang `>=` + Set theo dõi đã-nhận-chưa (giống `advanceSpecialist` đã làm đúng sẵn).
- **Mất reward killstreak khi claim fail**: `claimNext` rút item khỏi hàng chờ TRƯỚC khi biết có
  cấp được không — fail thì mất luôn (VD admin xóa/đổi id súng đó khỏi catalog sau khi đã queue).
  Giờ đưa lại vào cuối hàng chờ, không chặn các reward khác.
- **Xóa hết preset về 0**: chặn xóa preset cuối cùng — tự hồi phục ở lần đăng nhập sau nhưng lúc
  đang online với 0 preset thì UI hỏng.
- **Cooldown khóa luôn cả UI**: trước giờ trong cooldown player không mở được gunsmith/perk picker
  luôn (không chỉ bị chặn equip) — 3 chỗ trong LoadoutScreen dùng nhầm cờ `editable()` (cờ báo được
  equip hay không) để khóa việc BẤM MỞ slot. Server vốn đã cho sửa tự do (`C2SSaveLoadout` không hề
  check cooldown), giờ client cũng vậy — chỉ nút Equip mới còn bị giới hạn.
- **Memory leak nhỏ**: `EditRuleEngine.forget()` (logout) quên dọn map `LAST_EQUIP_TICK` — server
  chạy lâu, nhiều người ra vào thì map phình vô hạn.
- **Thiếu validate 3-perk Specialist**: `C2SSetSpecialist` không giới hạn số lượng perk client gửi
  lên — giờ chặn cứng ở 3.
- **Quick Fix chưa từng chạy**: perk có mô tả "kills kick-start regen" nhưng chưa implement gì —
  giờ có Regeneration II 4s sau mỗi kill (config được, xem dưới).
- **3 comment tự mâu thuẫn** về mốc Specialist (kills 3/4/5 + full unlock kill 6) — sai, không khớp
  hằng số thật trong code (2/4/6 + full unlock kill 8, đúng như đã ghi ở v2.11.0). Đã sửa lại cho
  khớp code thật.

**Perk giờ config được qua tab PERKS (field `effects` JSON) hoặc perks.json — không cần sửa code:**
- Shrapnel: `resistPercent` (mặc định 80).
- Double Time: `speedPercent` cho tốc độ khi cúi (mặc định 40) — tốc độ đi thường +10% vẫn chỉnh
  qua field `attribute` amount như cũ.
- Scavenger (khi kill): `minPercent` / `maxPercent` (mặc định 10/20, random giữa 2 mốc).
- Tracker: `durationSeconds` cho Glowing (mặc định 1).
- Fragiles: `durationSeconds` cho Wither (mặc định 2).
- Quick Fix: `durationSeconds` + `level` cho Regeneration (mặc định 4s, level 2 = Regen II).
- High Alert: thêm `rangeBlocks` (mặc định 48) + `coneDegrees` (mặc định 14, góc nửa nón phát hiện)
  bên cạnh `players`/`mobs`/`exemptTag` đã có từ trước.
- Tất cả đọc qua `PerkEffects.paramsForKey()` mới — tra cứu global theo key, không cần preset đang
  mặc, nên Specialist-granted cũng đọc đúng params như loadout thường.
- **Bổ sung theo yêu cầu**: Tracker và Fragiles giờ có thêm `level` (amplifier) bên cạnh
  `durationSeconds` — Fragiles đặc biệt đáng chỉnh vì Wither level ảnh hưởng sát thương/tick thật.
  Sửa nốt 1 comment cũ ở Shrapnel ghi "60%" trong khi code (và default config) là **80%** — đúng
  theo v1.6.1 "bản chốt", không phải bug logic, chỉ comment lỗi thời.
- **Admin Screen giờ có ô nhập số riêng cho từng perk** — không cần gõ JSON tay nữa: chọn perk ở
  tab PERKS (VD Shrapnel), một ô "resistPercent" tự hiện ngay bên dưới, gõ số rồi Save là xong.
  Mỗi perk chỉ hiện đúng field nó có (Shrapnel → resistPercent; Fragiles → durationSeconds +
  level; High Alert → rangeBlocks + coneDegrees...). Ô "effects" JSON thô vẫn còn cho ai muốn
  chỉnh tay/nâng cao, nhưng ô số mới luôn được ưu tiên khi lưu.

## v3.1.0 — Blueprint (MW2022-style platform switching)
Gunsmith giờ có mục **Blueprints**, nằm cạnh nút "Save Build":
- **Catalog**: mỗi entry súng TACZ có toggle `hasBlueprint` (mặc định false). Bật lên thì hiện ô
  `blueprintIds` (danh sách id catalog khác, phân cách bằng dấu phẩy — giống hệt kiểu whitelist/
  blacklist đã có sẵn). Súng nào TACZ cũng được, không giới hạn.
- Những entry được liệt kê trong `blueprintIds` **không còn hiện trong danh sách chọn Primary/
  Secondary nữa** — chỉ còn khẩu súng "nền tảng" (entry có `hasBlueprint=true`) hiện ra ở đó.
- Trong Gunsmith của khẩu súng nền tảng (hoặc bất kỳ blueprint con nào của nó), nút **Blueprints**
  mở 1 panel gọn (icon + tên, không stats/search/category — nhẹ hơn hẳn màn chọn súng đầy đủ) liệt
  kê nền tảng + toàn bộ blueprint con, đánh dấu cái đang chọn. Bấm 1 cái khác → chuyển hẳn dạng
  súng, gunsmith mở lại mới hoàn toàn cho súng đó (stats, mount điểm, build đều tính lại đúng cho
  chính súng mới — mỗi blueprint nhớ build riêng của nó, không lẫn giữa các dạng).
- Không cần sửa gì phía server validate (`sanitize`/`allowedIn`) — blueprint con vẫn là 1
  CatalogEntry hợp lệ bình thường, chỉ bị ẩn khỏi list chọn ban đầu thôi.

## v3.1.1 — HOTFIX Blueprint (2 bug thật + làm rõ giới hạn kỹ thuật)
- **Fix real-time**: bật toggle `hasBlueprint` giờ hiện field `blueprintGameIds` NGAY LẬP TỨC,
  không cần đóng/mở lại panel. Nguyên nhân cũ: field chỉ được tạo khi mở form (`open()`), toggle
  click không trigger `layoutForm()` lại.
- **Fix gốc rễ — sai kiểu ID**: field trước đây tra cứu giá trị nhập vào như CATALOG ID nội bộ
  (`registry.entry(id)`), trong khi đúng ra phải tra theo **TACZ gameId** (`tacz:g36k`,
  `sigma:g36c`...) như bạn nhập — đây là lý do "thêm id không có tác dụng" và "nút Blueprints
  không hiện". Đã đổi hẳn sang tra bằng `entryByGameId()` (súng nào cũng auto-seed sẵn 1 catalog
  entry ngay khi server start, nên gameId từ BẤT KỲ addon pack nào đã cài/load đều dùng được ngay,
  không cần setup gì thêm). Field đổi tên thành `blueprintGameIds` cho rõ, tránh nhầm lần sau.
- **Giữ attachment khi chuyển dạng**: đổi từ "xóa sạch" sang "giữ nguyên" khi switch blueprint.
- **Giới hạn kỹ thuật cần nói rõ**: TACZ dùng 1 item chung (Modern Kinetic Gun) và NBT tag `GunId`
  quyết định TOÀN BỘ danh tính khẩu súng — model, animation, âm thanh, ammo, VÀ tương thích phụ
  kiện đều đọc từ đúng 1 tag đó, không có lớp "chỉ đổi skin" tách biệt trong TACZ. Vậy nên đổi
  blueprint = đổi luôn GunId thật, không phải chỉ đổi hình. Attachment được giữ lại (thử install
  lại y hệt) nhưng nếu 2 súng có rail khác nhau, cái không tương thích sẽ tự bị bỏ qua (TACZ tự xử
  lý, không crash) — không thể đảm bảo 100% giống hệt như MW thật nếu 2 khẩu súng gốc TACZ vốn có
  layout phụ kiện khác nhau. Đây là giới hạn của chính TACZ, không phải mod thiếu code.

## v3.2.0 — Blueprint bugfix nghiêm trọng + Gunsmith polish + category rename
**Bug nghiêm trọng đã sửa:**
- **Súng blueprint tự "biến mất" và bị random súng khác thay thế** (báo bởi user, ảnh hưởng người
  chơi mới/level thấp): nguyên nhân là `sanitize()` lúc **Save** dùng level thật của player để
  check `unlockLevel`, nhưng `apply()` lúc equip/respawn lại dùng `Integer.MAX_VALUE` (tắt hẳn
  check) — 2 chỗ không đồng bộ. Súng blueprint con (tự auto-seed hoặc do admin từng cấu hình riêng
  trước đó) có thể có `unlockLevel` khác súng nền tảng → bị **Save** âm thầm loại bỏ ở người chơi
  level thấp → slot trống → `autoFillEmptySlots` random súng khác vào thế chỗ. Fix: blueprint con
  giờ **kế thừa `unlockLevel` của súng nền tảng** khi validate — đã unlock nền tảng thì mọi dạng
  của nó đều dùng được, không bị chặn lẻ tẻ theo từng biến thể nữa.
- **Combat-lock chặn nhầm player túi đồ trống**: player mới join giữa trận, chưa có gear gì, vẫn bị
  áp luật "vừa dính damage/HARD-chỉ-đổi-khi-chết/cần-zone" y hệt người đã có đồ — vô lý vì không có
  gì để "đổi bất công" cả. Giờ: player đang giữ **0 item do mod này cấp** thì được bỏ qua MỌI giới
  hạn equip (combat/zone/HARD), lấy loadout đầu tiên ngay lập tức.

**Gunsmith Blueprint — polish theo yêu cầu:**
- Nút "Blueprints" chuyển xuống **góc trái dưới cùng** (thẳng hàng cột Type Rail), tránh hẳn vùng
  hover/scroll của candidate list bên phải.
- **So sánh stat khi hover**: trỏ chuột vào 1 biến thể (khác súng đang mở) trong panel Blueprints
  hiện ngay bảng so sánh 6 chỉ số (ACCURACY/DAMAGE/RANGE/FIRE RATE/MOBILITY/CONTROL, stock vs
  stock) với mũi tên xanh/đỏ/= — dùng lại đúng dữ liệu `TaczStats.barStats` của panel stat chính.

**Category tabs (SlotPickerScreen) — đổi tên + sắp xếp + scale:**
- Tên hiển thị: ASSAULT RIFLES · SMGS · SHOTGUNS · LMGS · MARKSMAN RIFLES · SNIPER RIFLES (map từ
  category code nội bộ ar/smg/sg/lmg/dmr/sr — dữ liệu gốc không đổi, chỉ đổi cách hiển thị).
  Category khác (pistol, launcher...) vẫn hiện, xếp sau 6 cái này theo đúng thứ tự cũ.
  Đồng bộ áp dụng cho cả detail panel, grid row, và cả tìm kiếm (gõ "assault" giờ ra súng AR).
- Text tab giờ vẽ ở **80% scale** (kèm giảm padding) để 6 tên dài không tràn ngang màn hình ở độ
  phân giải nhỏ — vẫn giữ auto-width theo độ dài chữ, chỉ scale nhỏ lại.

**Admin Screen — theo yêu cầu trước:**
- Field `blueprintGameIds` giờ hiện **validate real-time** ngay dưới ô nhập: ✓ xanh (tìm thấy +
  tên hiển thị) / ✗ đỏ (không tìm thấy hoặc đang bị disable) cho từng gameId đang gõ, không cần
  bấm Save mới biết đúng/sai.

## v4.0.0 — Blueprint reliability + Gunsmith/picker overhaul + QoL
**Blueprint: tìm và vá gốc rễ bug "equip lúc được lúc không"**
- **`entryByGameId()` hết mù trước bản disable**: trước đây hàm này trả về "match đầu tiên gặp"
  trong catalog, kể cả khi đó là 1 bản đã bị `enabled=false` trong khi vẫn còn 1 bản khác cùng
  gameId đang sống khỏe re re (dễ xảy ra khi vừa có entry auto-seed từ weaponscan vừa có entry
  admin tự thêm tay cho cùng 1 khẩu súng TACZ). Giờ luôn ưu tiên bản đang enabled, chỉ fallback về
  bản disable nếu thật sự không còn lựa chọn nào khác.
- **`sanitize()` không còn drop thẳng tay 1 blueprint form về random nữa**: khi 1 form blueprint
  tạm thời không hợp lệ (bị disable, đổi category, hoặc biến mất tạm thời khỏi creative tab lúc
  world load chậm), thay vì vứt cả slot cho `autoFillEmptySlots` random 1 khẩu bất kỳ trong toàn
  bộ catalog, giờ tự fallback về **súng nền tảng (platform)** của chính blueprint đó trước. Kết quả:
  tệ nhất bạn nhận lại súng gốc của khẩu đó, không bao giờ nhận súng ngẫu nhiên không liên quan.
- **`CreativeTabScanner` không còn tự disable súng oan vì timing lúc server start**: quét creative
  tab tự động sau `ServerStartedEvent` có thể bắt trúng lúc 1 gunpack addon (đặc biệt là blueprint
  child) chưa kịp đăng ký xong — trước đây 1 lần quét thiếu là disable luôn. Giờ cần **2 lần quét
  liên tiếp** đều không thấy mới thực sự disable, hấp thụ đúng loại hiccup khởi động này.
- **Lệnh mới `/mwloadout diagnose`** (admin): quét toàn catalog tìm gameId trùng lặp, kiểm tra
  từng blueprint form còn resolve/enabled không, **tự dọn blueprintId chết** (không trỏ tới entry
  nào cả — rác vô hại nên xóa thẳng), và kiểm tra preset của chính admin xem slot nào sắp bị
  random-fill ở lần apply tiếp theo. Toàn bộ chi tiết log ra server log với tag
  `[MWLoadout][diagnose]`.
- **Logging chi tiết theo dấu mọi quyết định**: `sanitize()` (drop/phục hồi vì sao), `autoFill-
  EmptySlots()` (slot nào bị random và random thành gì), `entryByGameId()` (cảnh báo gameId
  trùng), `apply()` (before/after theo từng người chơi) — mọi bug tương tự về sau đều để lại dấu
  vết thay vì chỉ thấy "tự nhiên súng bị đổi".
- **Pin súng đang trang bị 1 blueprint variant lên đầu list**: chọn blueprint child bị ẩn khỏi
  picker list nên trước đây logic "pin lên đầu + scroll tới vị trí đang chọn" so sai id, không bao
  giờ khớp. Giờ resolve về platform id trước khi so sánh — súng nào bạn đang dùng 1 dạng blueprint
  của nó sẽ luôn nổi lên đầu trang.
- **Hiển thị số blueprint khả dụng khi hover**: panel stat bên phải giờ hiện thêm dòng
  "N BLUEPRINTS AVAILABLE" (tính real-time, không cache) khi hover/chọn 1 khẩu có blueprint. Thêm
  badge "BP" nhỏ ngay trong list để nhận ra súng nào có blueprint mà không cần hover từng khẩu.

**Category tabs & search bar**
- **Hết font răng cưa**: bỏ scale 0.8x (Minecraft bitmap font scale lẻ luôn bị vỡ nét) — tab giờ
  vẽ full scale 1.0 giống mọi chỗ khác trong UI, bù lại bằng spacing rộng hơn thay vì ép chữ nhỏ.
- **Fix text lệch tâm trong box**: trước đây offset trái cố định `+8px` bất kể độ rộng box khiến
  bên phải luôn thừa khoảng trắng nhiều hơn bên trái — giờ tính đúng theo tâm box.
- **Thêm hover animation cho tab**: dùng lại `MWStyle.easeHover()` sẵn có trong mod (đúng convention,
  không tự chế cơ chế mới) — nền và màu chữ chuyển mượt khi rê chuột qua tab chưa active.
- **Search bar dời lên góc phải trên**, ngay phía trên panel stat/hover-info súng thay vì nằm cạnh
  hàng tab — về cấu trúc không thể bị tab đè lên nữa dù có bao nhiêu category hay tên dài cỡ nào.
  Thêm nút clear (×) — 1 click xóa search thay vì giữ backspace.
- **Category `br` hiển thị "Battle Rifles"** thay vì rơi về raw string "BR".

**QoL khác**
- **Duplicate Class**: thêm vào context-menu chuột phải của preset rail — nhân bản 1 class thành
  bản copy mới ở cuối danh sách (không đụng tới class đang active/trang bị), tự dùng lại logic
  dedupe tên sẵn có nên copy tên đụng hàng sẽ tự thành "Tên 2" thay vì trùng y hệt.


## v4.0.0.1 — HOTFIX compile
`Net.java` không build được: `dedupeName(...)` gọi từ `C2SPresetOp` (case "duplicate") nhưng hàm
này là `private static` khai báo bên trong `C2SSaveLoadout` — 2 class khác nhau tuy cùng lồng
trong `Net`, Java không cho gọi trực tiếp không ghi rõ tên class trong trường hợp này. Sửa: qualify
thành `C2SSaveLoadout.dedupeName(...)`. Đã quét toàn bộ `Net.java` xem còn method `private static`
nào khác bị gọi xuyên class kiểu này không — không còn.

## v4.1.0 — Exclusive Test Weapons (admin-only)
Catalog entries (GUN) giờ có toggle **adminOnly** trong tab CATALOG:
- Player không có quyền OP: hoàn toàn không thấy súng này tồn tại — server **không hề gửi** entry
  đó xuống client của họ (lọc ngay lúc sync registry theo permission từng player), không phải chỉ
  ẩn ở UI. Kể cả client bị chỉnh sửa cũng không có dữ liệu để gọi ra.
- Không thể lưu/trang bị được (server validate riêng, kể cả cố tình gửi packet thủ công cũng bị
  chặn) và **không bao giờ** lọt vào pool random-fill cho player thường.
- Player OP: thấy và dùng bình thường như mọi súng khác trong catalog, không có gì khác biệt.
- Fix nhỏ đi kèm: form Save của tab CATALOG trước đây tạo entry hoàn toàn mới mỗi lần lưu, vô tình
  xóa mất cờ `stale` (đánh dấu ghost-ID của scanner) mỗi khi admin chỉnh sửa bất kỳ thứ gì khác
  trên entry đó — giờ giữ lại đúng trạng thái stale khi save.

## v4.2.0 — Ghost ID / Stale Weapon Reconciliation (optimized)
Hoàn thiện phần "ghost item" đã tạm dừng trước đó:
- **Scanner giờ phân biệt rõ 3 trạng thái**: Total / Active / Stale — mỗi entry có cờ `stale`
  riêng (khác hẳn `enabled` do admin tự tắt), nên cleanup không bao giờ đụng nhầm vào thứ admin cố
  ý tắt.
- **Tự phục hồi**: nếu pack chứa 1 khẩu súng từng bị flag stale được cài lại, lần scan kế tiếp thấy
  lại súng đó trong creative tab sẽ tự bật lại + xóa cờ stale — không cần admin nhớ để bật tay.
  (Đây là bug thật tìm thấy trong lúc viết lại: code cũ SKIP hẳn các entry đã bị tắt nên không bao
  giờ tự hồi phục được dù súng quay lại.)
- **Admin Panel, tab CATALOG**: dòng tổng kết "Total X | Active Y | Stale Z" (đỏ khi có stale), 2
  nút mới **Reconcile** (ép quét lại + đối chiếu ngay) và **Clean Stale** (xóa toàn bộ entry đang
  bị flag stale). Row nào bị stale trong danh sách hiện đánh dấu "!" màu đỏ để nhận ra ngay.
- **Lệnh console**: `/mwloadout admin weaponscan` giờ báo cáo đầy đủ (Total→Active/Stale + số
  added/healed/newly stale/recovered). `/mwloadout admin cleanstale` liệt kê chính xác từng entry
  sắp xóa (id + gameId) trước khi xóa, kèm log server đầy đủ — không xóa âm thầm.
- Tất cả đường xóa (panel lẫn lệnh) đều dùng chung 1 hàm `LoadoutRegistry.removeEntries()` — không
  có 2 logic xóa khác nhau dễ lệch pha.

## v4.2.1 — HOTFIX bundle: ghost auto-purge, Quick Fix, Scavenger, Specialist, Field Upgrade, Killfeed
**"Ghost item chỉ báo mà không dọn"**
- v4.2.0 flag `stale` đúng nhưng KHÔNG BAO GIỜ tự xóa — admin phải nhớ bấm "Clean Stale" riêng thì
  mới dọn. Giờ: entry đã stale mà lần reconcile sau vẫn xác nhận **thật sự biến mất** qua chính
  registry của TACZ (`TimelessAPI.getCommonGunIndex`, không chỉ "không thấy trong creative tab")
  thì tự xóa luôn, không cần thao tác tay.

**Quick Fix (regen on kill) không hoạt động**
- Bug thật: `quickFixOnKill()` bị nhét trong block chỉ chạy khi **victim là ServerPlayer** (PvP
  only), trong khi mô tả perk là "kill kick-starts your regen" — không giới hạn PvP. Đa số kill là
  mob nên regen gần như không bao giờ trigger. Đã chuyển ra khối "any kill" cùng Killstreak.

**Scavenger refill đạn cho item killstreak**
- Bug thật: `onPlayerKill()` (refill đạn on-kill của Scavenger) chỉ check "đang cầm súng TACZ",
  không loại trừ súng killstreak (vốn có NBT tag riêng `STREAK_TAG`, thiết kế one-shot có chủ đích).
  Đã thêm check loại trừ qua `KillstreakItems.isStreakItem()`.

**Specialist "1 kill mở khoá hết perk"**
- Bug thật, khá nghiêm trọng: packet `C2SSetSpecialist` (bật/tắt hoặc đổi 3 perk Specialist)
  không hề reset `currentStreak`/`specialistPerksActivated`/`specialistFullUnlock`. Nếu người chơi
  đã có streak tích luỹ từ mode Item (hoặc lượt chọn perk cũ) rồi bật/đổi Specialist, phát kill đầu
  tiên sau đó có thể nhảy qua nhiều mốc 2/4/6/8 cùng lúc. Thêm
  `KillstreakManager.resetSpecialistProgress()`, gọi mỗi lần toggle/đổi perk — luôn bắt đầu sạch từ 0.

**Field Upgrade slot 7 tắt xong restart lại bật**
- Bug thật: `seedInto()` (auto-seed companion item vào slot extra1/2/3) set cờ `anyFound=true`
  ngay khi item TỒN TẠI trong game, kể cả khi item đó **đã có sẵn trong catalog từ trước** (không
  phải mới thêm) — nên mỗi lần server start đều ép `slot.enabled=true`, đè lên lựa chọn tắt thủ
  công của admin. Giờ chỉ auto-enable đúng 1 lần khi THỰC SỰ seed thêm entry mới vào slot, không
  bao giờ đụng lại sau đó.

**Kill banner / emblem không lưu, hay random**
- Trước đây toàn bộ dữ liệu lựa chọn card/emblem phụ thuộc 100% vào lưu trữ phía client của mod
  Killfeed (qua `KillfeedBridge` reflection) — không đáng tin cậy qua các lần rejoin. Giờ MWLoadout
  tự lưu lựa chọn vào **file data của player trong config** (field `killCard`/`killEmblem` trong
  `PlayerProfile`, packet `C2SSaveKillCard`): mỗi lần chọn card/emblem trong Customizations tự
  động lưu server-side, và mỗi lần login MWLoadout tự đẩy lại lựa chọn đã lưu vào Killfeed (đè lên
  bất kỳ giá trị nào Killfeed đang có) — sửa tận gốc vấn đề "random/không lưu".

**Bỏ keybind Claim Killstreak**
- Phát hiện: `queuedStreakItems` (hàng đợi mà keybind này rút ra) chưa từng được thêm vào từ bất kỳ
  đâu trong code — bấm phím này từ trước tới giờ **không hề làm gì cả**. Xóa hẳn keybind (an toàn
  100%, không mất tính năng thật nào); hạ tầng server-side (`claimNext`) vẫn giữ nguyên phòng khi
  cần gắn vào 1 nút UI sau này.

## v4.2.3 — Blueprint z-layer, perk randomization, player-data safety, picker entry QoL
**Blueprint panel bị icon phụ kiện đè lên (layer kì lạ)**
- Xác nhận qua tài liệu chính thức của Forge: `graphics.renderItem()` (icon phụ kiện trong list)
  mang theo logic Z-offset nội bộ riêng, khác hẳn `fill()`/`drawString()` phẳng — nên dù blueprint
  panel vẽ SAU về mặt thứ tự code, icon vẽ TRƯỚC đó vẫn có thể "đè" lên panel. Fix theo đúng cách
  Forge khuyến nghị: đẩy panel lên Z-layer cao hơn hẳn (`pose().translate(0,0,300)`) trước khi vẽ.

**Perk "invalid perk" — tìm ra root cause thật**
- `applyDefaultPerks()` hard-code sẵn 3 perk id cụ thể (`double_time`/`hardline`/`quick_fix`) —
  admin đổi tên/xóa/dời cột 1 trong 3 perk này là vỡ ngay, y hệt gốc rễ vấn đề trong ảnh chụp
  ("PERK 3: invalid perk"). Viết lại hoàn toàn theo đúng yêu cầu: **random hoàn toàn** từ perk còn
  enabled trong đúng cột đó, không hard-code gì cả — chạy mỗi khi tạo class MỚI lẫn khi heal 1 perk
  bị vỡ trên class cũ. Fix luôn 1 bug thật trong fallback cũ: không hề check `perk.enabled`, có thể
  chọn nhầm 1 perk đã bị admin tắt và lặp vỡ liên tục.
- Server-side (`sanitize()`) cũng học cùng triết lý: perk không hợp lệ giờ **random-thay thế** bằng
  1 perk hợp lệ khác cùng cột thay vì xóa trắng slot — chỉ xóa hẳn khi cột đó thực sự không còn
  perk nào khả dụng.

**Lưu dữ liệu player — an toàn và cẩn thận hơn**
- **Ghi atomic**: trước đây ghi thẳng vào file JSON cuối — nếu server crash/mất điện giữa lúc ghi,
  file có thể bị half-write, đọc lại thành "corrupt" và **mất sạch dữ liệu người chơi** (reset về
  mặc định). Giờ ghi ra file `.tmp` trước rồi rename atomic đè lên file thật — file cuối luôn hoặc
  là bản cũ nguyên vẹn hoặc bản mới nguyên vẹn, không bao giờ nửa vời.
- **Sao lưu khi phát hiện corrupt** thay vì xóa thẳng: file lỗi được đổi tên thành
  `<uuid>.json.corrupt-<timestamp>` trước khi tạo profile mới, phòng khi cần khôi phục tay sau này.
- **Bỏ qua ghi dư thừa**: nếu JSON giống hệt lần ghi gần nhất trong phiên, không ghi đĩa lại nữa.

**Weapon selection screen — cải thiện logic khi vào màn**
- Trước đây LUÔN mặc định vào category đầu tiên theo thứ tự khi mở picker, bất kể súng đang trang
  bị thuộc category nào — tốn thêm 1 cú bấm để tìm lại súng của mình. Giờ tự mở đúng category của
  súng hiện tại (kể cả blueprint child, resolve về category của platform).

## v4.2.4 — Class "heal" functions stomping admin edits (5 bug), Blueprint panel animation
**Throwable count tự reset về 2 mỗi lần restart — root cause**
- `healThrowableCounts()` chạy MỖI LẦN server start, ép `count = 2` cho MỌI item lethal/tactical
  auto-seed bất kể admin đã chỉnh gì — hoàn toàn không có cách phân biệt "chưa từng đụng tới" với
  "admin cố ý đổi thành 1". Đã xác nhận hàm này **hoàn toàn dư thừa**: `classify()` (tạo entry mới)
  đã set đúng default (melee=1, còn lại=2) ngay từ lúc tạo, một lần duy nhất. Xóa bỏ hẳn
  `healThrowableCounts()` — fix triệt để, không còn gì đè lên số lượng admin đã set nữa.

**Rà thêm 4 bug cùng họ (không ai báo nhưng cùng root cause)**
Cùng 1 dạng lỗi: hàm "heal" chạy mỗi lần server start, check "giá trị hiện tại có phải default cũ
không" rồi ép về giá trị mới — không có cách phân biệt "chưa từng đụng" với "admin cố ý đặt lại
giá trị y hệt default cũ". Đã thêm cờ migration 1-lần (`presetCeilingMigrated`,
`extra3OptionalMigrated`, `pufferfishMigrated`, `shrapnelMigrated` trong `Rules.java`) cho cả 4:
- `healPresetCeiling`: ép `maxPresets` từ 10 lên 20 mỗi lần nếu đúng bằng 10 — admin đặt lại 10 sẽ
  bị đè lại thành 20 vĩnh viễn.
- `healPufferfish`: tự tạo lại item Pufferfish nếu admin **xóa nó đi** — không bao giờ xóa được.
- `healExtra3Optional`: ép `extra3.optional = true` mỗi lần nếu admin tắt nó.
- `healShrapnel`: có thể đè mô tả perk Shrapnel admin tùy chỉnh nếu không bắt đầu bằng "Resistant".

**Animation cho Blueprint panel**
- Panel giờ fade-in + trượt nhẹ lên từ nút bấm thay vì bật/tắt cứng ngay lập tức — dùng lại đúng
  `MWStyle.easeHover()` convention đã có sẵn trong mod, animate cả lúc đóng lại (không cắt hình
  đột ngột).



## v4.2.2 — HOTFIX compile + Scavenger any-kill
- Fix lỗi build thật: thiếu `import dev.l41nx.mwloadout.server.KillstreakManager;` trong
  `Net.java` (dùng trong fix reset Specialist ở bản 4.2.1) — không compile được.
- Nhân tiện rà thêm: `PerkEffects.onPlayerKill()` (Scavenger refill đạn khi kill) bị nhét chung
  block PvP-only với XP — cùng loại bug với Quick Fix ở bản trước, chỉ khác là chưa ai báo. Scavenger
  là utility perk (không phải phần thưởng kinh tế cần chống farm mob như XP) nên đúng ra phải chạy
  trên MỌI kill. Đã chuyển ra khối "any kill" cùng Killstreak và Quick Fix.

## v4.3.0 — Admin panel: Stats dashboard (pick rate, kills, headshot %, TTK)
Tab **STATS** mới trong admin panel — biểu đồ cột kiểu chứng khoán, hover vào cột hiện tên +
thông số chi tiết, đúng như yêu cầu.

**Tracking (server-side, `UsageStats` mới trong `registry/`)**
- **Pick rate / top tier**: đếm mỗi lần 1 khẩu súng / perk được TRANG BỊ (equip time — tính cả
  respawn, không chỉ lúc chọn trong menu — phản ánh đúng "vũ khí nào thực sự được dùng nhiều" hơn
  là chỉ đếm click chọn 1 lần).
- **Kill / headshot ratio / TTK / hits landed per súng**: dùng API chính thức đã verify qua GitHub
  TACZ (`EntityKillByGunEvent` cho kill+headshot, `LivingHurtEvent` + `IGun` chuẩn cho hit/TTK) —
  key theo TACZ gameId (không phải catalog id) nên **blueprint tự động tính riêng** từng dạng,
  đúng yêu cầu "blueprint tính riêng".
  - *Lưu ý trung thực*: TACZ không public event "bắn" (shots fired) nên "độ chính xác" thật
    (hit/shot) không track được đáng tin cậy — dùng "hits landed" thay thế, cộng headshot ratio để
    bù phần đo độ chính xác.
- **Player**: tổng số lần equip theo từng người chơi (top active players).
- Lưu `config/mwloadout/stats.json`, ghi atomic (temp+rename, học từ fix `ProfileManager` bản
  trước), flush mỗi 20 sự kiện thay vì mỗi lần (tránh spam ghi đĩa lúc combat dồn dập).

**UI trong Admin Panel**
- 4 view: **WEAPONS / PERKS / PLAYERS / CATEGORIES**, đổi qua pill button trên đầu.
- Biểu đồ cột dọc kiểu chứng khoán, sort giảm dần theo lượt pick, hover 1 cột → tooltip hiện tên +
  chi tiết (weapons: pick count, kills, hits landed, headshot ratio, avg TTK).
- Nút **RESET STATS** — admin-only qua packet riêng (server-authoritative, không cho client tự xóa
  dữ liệu của chính nó).
- Dữ liệu chỉ đồng bộ khi mở tab STATS (không tự sync lúc join như registry — chỉ admin mới xem).

## v4.3.1 — HOTFIX compile (switch STATS) + Extended Mag ammo không cập nhật
**Fix lỗi build**: 2 switch expression trên biến `tab` (`onDataRefreshed()`, `tick()`) chưa cover
giá trị `Tab.STATS` mới thêm ở bản 4.3.0 — Java bắt buộc switch expression phải exhaustive. Đã
thêm case cho cả 2 (2 chỗ khác dùng switch statement/đã có `default` sẵn nên an toàn, không cần sửa).

**Extended Mag không cập nhật đạn — tìm ra root cause thật**
`TaczStackFactory.buildGun()` bị sai THỨ TỰ nghiêm trọng:
1. Tính dung lượng băng đạn CÓ tính attachment (`getAmmoCountWithAttachment`) — nhưng lúc này
   attachment **CHƯA ĐƯỢC INSTALL** vào stack, nên hàm đọc thấy 0 attachment và tính ra dung lượng
   BASE (chưa có Extended Mag).
2. Ngay sau đó, 1 dòng khác **ghi đè** giá trị vừa tính bằng `index.getGunData().getAmmoAmount()`
   — dung lượng base thô, không tính attachment gì cả — xóa sạch nỗ lực tính đúng ở bước 1.
3. Attachment (bao gồm Extended Mag) mới được install SAU CÙNG — không có gì tính lại đạn sau đó.

Kết quả: súng luôn build ra với đạn base, bất kể có Extended Mag hay không — đúng y hệt triệu chứng
bạn báo. Đây cũng giải thích vì sao **refill kit không giải quyết được**: refill chỉ top-up đạn
hiện tại lên ĐÚNG BẰNG cap đã lưu sẵn trong NBT, không tính lại cap — cap đã sai từ lúc build thì
refill bao nhiêu lần cũng vô ích.

Đã sửa lại đúng thứ tự: install attachment TRƯỚC → tính dung lượng CÓ attachment SAU → set NBT
đúng 1 lần duy nhất qua API chính thức của TACZ (`setCurrentAmmoCount`), không còn 2 lệnh ghi đè
nhau nữa. Vì cơ chế "refill" duy nhất trong mod là respawn restock (gọi lại đúng hàm này), fix này
tự động giải quyết luôn cả 2 phần của bug report — không cần sửa gì thêm ở chỗ khác.

## v4.3.2 — HOTFIX layout Stats tab + bug phá hoại perks.json nghiêm trọng
**Stats tab: dòng summary đè lên hàng pill WEAPONS/PERKS/PLAYERS/CATEGORIES**
- Lỗi tính sai tọa độ Y đơn giản — dòng "Total picks tracked..." vẽ ở y=62, nằm giữa khoảng
  50-68 của hàng pill. Đã tính lại toàn bộ layout theo thứ tự đúng: pill → summary → chart.

**`perks.json` bị đè mỗi lần restart — root cause nghiêm trọng hơn nhiều so với các bug "heal" khác**
`reseedPerks()` gọi `perks.clear()` + `perkSlots.clear()` **XÓA SẠCH TOÀN BỘ PERK** (kể cả perk
admin tự tạo, không chỉ 15 perk mặc định) rồi tạo lại từ hardcode trong `Defaults.java` — chạy tự
động, âm thầm mỗi khi `perkRevision < 6` (tức là bất kỳ config nào chưa qua đúng lần scan này).
Comment cũ ghi "admin-created perks untouched" nhưng code thực tế hoàn toàn ngược lại.

Đã tách riêng 2 khái niệm bị nhầm lẫn làm một trước đây:
- **`reseedPerks()`** — giữ nguyên hành vi phá hoại (xóa sạch + tạo lại), nhưng giờ CHỈ dùng cho
  lệnh admin tường minh `/mwloadout admin resetperks` (admin chủ động gõ lệnh muốn reset sạch —
  đây là "escape hatch" có chủ đích, cần giữ nguyên).
- **`ensureDefaultPerksSeeded()`** (mới) — additive-only, dùng `upsertPerkIfAbsent`/
  `addPerkSlotIfAbsent`, không bao giờ đụng vào perk/column đã tồn tại. `healPerkTuning()` (chạy
  tự động mỗi server start) giờ gọi hàm NÀY thay vì hàm phá hoại — perk hay column nào admin đã có
  (dù là 1 trong 15 perk mặc định đã tùy chỉnh, hay perk tự tạo hoàn toàn mới) sẽ không bao giờ bị
  đè hay xóa nữa, chỉ những perk THỰC SỰ THIẾU mới được thêm vào.

## v4.3.3 — Fire mode (semi/burst/auto) không lưu + lỗ hổng spam refill loadout
**Fire mode không lưu trong loadout — tìm ra root cause**
`TaczStackFactory.buildGun()` LUÔN ép súng về fire mode ĐẦU TIÊN trong danh sách của súng đó
(`fireModes.get(0)`) mỗi lần build — không hề có chỗ nào lưu hay đọc lại lựa chọn semi/burst/auto
của người chơi. Nếu đổi fire mode trong game (qua cơ chế TACZ), lựa chọn đó mất ngay khi
respawn/equip lại vì bị build lại từ đầu.

Đã thêm hỗ trợ lưu fire mode, tận dụng đúng cấu trúc dữ liệu có sẵn (không đổi schema):
- Fire mode lưu như 1 key đặc biệt (`__fireMode`) ngay trong Map `attachments` mà mỗi build vốn
  đã có sẵn — tự động đi theo đúng luồng lưu/đồng bộ hiện có (4 build nhớ theo súng, save build,
  respawn restock) mà không cần thêm packet hay đổi cấu trúc `PlayerProfile`/`LoadoutPreset`.
- Thêm nút **MODE: [SEMI/BURST/AUTO]** trong Gunsmith (góc dưới trái, cạnh nút Blueprints) — chỉ
  hiện khi súng đó thực sự có nhiều hơn 1 fire mode để chọn. Bấm để chuyển vòng qua các mode.
- `buildGun()` giờ đọc lại giá trị đã lưu, chỉ fallback về mode đầu tiên nếu chưa từng chọn hoặc
  súng đã đổi (blueprint switch) không còn hỗ trợ mode đã lưu.

**Lỗ hổng: spam refill loadout khi đứng trong spawn/edit zone**
`EditRuleEngine.check()` (gác cổng cho phép equip hay không) có nhánh: đứng trong spawn zone hoặc
edit zone → cho phép equip **hoàn toàn không qua bất kỳ cooldown nào** (`return Verdict.yes();`
ngay lập tức, bỏ qua toàn bộ hệ thống cooldown theo độ khó 10-30s bên dưới). Vì equip luôn refill
đầy đủ đạn + kit, đứng trong zone và spam bấm EQUIP liên tục = refill vô hạn không giới hạn, bỏ qua
hoàn toàn cơ chế cooldown vốn được thiết kế cẩn thận cho các trường hợp khác.

Đã thêm 1 tầng chặn tối thiểu **luôn luôn áp dụng** (1 giây), kể cả khi đang trong zone — không đủ
lâu để cản trở việc chỉnh loadout bình thường trước khi triển khai, nhưng đủ để spam-click không
còn tác dụng farm refill vô hạn nữa. Cooldown dài theo độ khó (10-30s) bên ngoài zone giữ nguyên
không đổi.

**Cập nhật theo yêu cầu**: tầng chặn 1 giây này giờ là **toggle riêng trong Admin Panel → RULES**
(`antiSpamRefillEnabled`), **mặc định TẮT** — code đã sẵn sàng nhưng không tự động bật, admin bật
lên khi nào cần.

## v4.3.4 — Fire mode thực sự hoạt động, stats race condition, cân bằng Quick Fix/Fragiles
**Fire mode "chưa hoàn thiện" — tìm ra bug thật: bị server tự xóa ngay sau khi lưu**
Nút MODE ở bản 4.3.3 lưu đúng nhưng **`sanitizeAttachmentMap()`/`sanitizeAttachments()`** (chạy ở
server MỖI LẦN save/equip/respawn) coi giá trị fire mode ("SEMI"/"BURST"/"AUTO") như 1 attachment
id thật, đem parse thành `ResourceLocation` → **throw exception vì chữ hoa không hợp lệ** → bị catch
và xóa luôn khỏi map. Kết quả: lựa chọn fire mode gần như không bao giờ sống sót qua 1 lần save.
Đã sửa cả 2 hàm — tách riêng fire mode ra trước khi validate, không đem qua bước check
ResourceLocation nữa, validate riêng bằng đúng enum `FireMode`.

**Đổi fire mode bị tính điểm attachment**
Do fire mode lưu chung map với attachment thật, mọi chỗ tính "số điểm đã dùng" bằng
`.size()` của map đều bị dư 1. Đã thêm `realAttachmentCount()` loại trừ key fire mode, áp dụng cho
cả hiển thị điểm lẫn logic chặn khi đầy điểm.

**Player/Stats tracker "tự reset", hiển thị sai lúc đổi loadout**
Root cause: `UsageStats` là 1 singleton dùng chung — trong **singleplayer**, client và server nằm
CHUNG 1 JVM nên CHUNG 1 object. Mở tab Stats → client gọi `fromSyncJson()` (clear rồi điền lại) lên
**đúng object mà server đang ghi trực tiếp mỗi kill/pick** → race condition giữa 2 luồng, dữ liệu
ghi đè lẫn nhau ngẫu nhiên. Đã sửa 2 lớp:
- Bỏ qua toàn bộ round-trip sync khi phát hiện đang chạy singleplayer/LAN host
  (`Minecraft.getInstance().hasSingleplayerServer()`, check ở phía client) — client đã có sẵn dữ
  liệu sống, không cần sync gì cả.
- Đổi toàn bộ `HashMap` trong `UsageStats` sang `ConcurrentHashMap` — phòng hộ thêm cho trường hợp
  dedicated server nhiều luồng.

**Cân bằng perk theo yêu cầu**
- **Quick Fix**: giờ có thêm hiệu ứng **Regeneration I thụ động vĩnh viễn** (song song với burst
  hồi máu tức thì lúc kill — giữ nguyên không đổi), và **kháng hiệu ứng Wither của Fragiles**
  (Fragiles vốn dùng Wither để chặn hồi máu — Quick Fix giờ miễn nhiễm đúng theo yêu cầu cân bằng).

⚠️ *Lưu ý*: do fix trước đó (v4.3.2) đã bảo vệ `perks.json` khỏi bị đè, thay đổi Quick Fix ở
`Defaults.java` **sẽ không tự áp dụng lên perk `quick_fix` đã có sẵn trong file config của bạn** —
cần thêm tay hiệu ứng `{"type":"mob_effect","effect":"minecraft:regeneration","amplifier":0}` vào
perk đó qua Admin Panel, hoặc xóa perk `quick_fix` khỏi file để mod tự seed lại bản mới.

## Thư mục dữ liệu

Dữ liệu không còn nằm trong `config/`. Một thư mục duy nhất `mwloadout/` ở thư mục game (client) hoặc thư mục server (dedicated); đổi chỗ bằng `-Dmwloadout.dir=<đường dẫn>`.

| Thư mục | Chứa | Bên nào dùng |
|---|---|---|
| `mwloadout/data/` | slots, catalog, perks, rules, stats - toàn bộ thiết lập game | server |
| `mwloadout/players/` | một file json mỗi người chơi: class và gun build | server (và world đơn) |
| `mwloadout/cloud/` | bản copy class của chính bạn theo UUID tài khoản, `backups/` giữ 5 bản gần nhất | client |
| `mwloadout/client/` | dữ liệu riêng của client (cài đặt Advanced stats) | client |

Lần chạy đầu sau khi cập nhật, mod **sao chép** (không xóa, không ghi đè) mọi thứ từ `config/mwloadout/` sang đây, và để lại `MOVED.txt` ở thư mục cũ. Các file `.toml` vẫn ở `config/`.

**Cloud hai chiều:** nút `CLOUD` đẩy bản trên máy lên server; nút `TO DEVICE` (chỉ hiện khi đang ở server từ xa) lưu các class server đang giữ cho bạn về máy, sao lưu bản cũ trước. Vào world đơn rồi bấm `CLOUD` để áp dụng bản đó.
