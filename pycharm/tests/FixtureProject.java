package autoepicseven.preview;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

/** A reproducible ALAS-shaped project, independent of a game checkout or account. */
public final class FixtureProject {
    private static final String IMPORT = "from module.base.button import Button, ButtonWrapper\n";

    public static void main(String[] arguments) throws Exception {
        Path root = Path.of(arguments[0]);
        write(root, "module/base/button.py", "class Button: pass\nclass ButtonWrapper: pass\n"
                + "raise RuntimeError('Tests must only inspect Python statically')\n");
        StringBuilder page = new StringBuilder(IMPORT + "MAIN_GOTO_SECRET_SHOP = ButtonWrapper(name='MAIN_GOTO_SECRET_SHOP',\n");
        for (String namespace : new String[]{"cn", "global_cn"}) {
            page.append(namespace).append("=(\n");
            for (int frame = 0; frame < 2; frame++) {
                String file = "assets/" + namespace + "/base/page/MAIN_GOTO_SECRET_SHOP"
                        + (frame == 0 ? "" : ".2") + ".png";
                image(root, file);
                page.append(button(file, frame == 0 ? "26, 279, 39, 292" : "26, 357, 39, 370")).append(",\n");
            }
            page.append("),\n");
        }
        page.append("global_en=None)\n");
        write(root, "tasks/base/assets/assets_base_page.py", page.toString());
        String icon = "assets/share/base/resource_bar/SKYSTONE_ICON.png";
        image(root, icon);
        write(root, "tasks/base/assets/assets_base_resource_bar.py", IMPORT
                + "SKYSTONE_ICON = ButtonWrapper(name='SKYSTONE_ICON', share=" + button(icon, "1, 1, 14, 14") + ")\n");
        write(root, "tasks/secret_shop/secret_shop.py",
                "from tasks.base.assets.assets_base_resource_bar import SKYSTONE_ICON\nresult = SKYSTONE_ICON\n");
        String selectedCn = "assets/cn/activity/special/26_8_27/FREE_20_GACHA_SELECTED.png";
        String selectedGlobal = "assets/global_cn/activity/special/26_8_27/FREE_20_GACHA_SELECTED.png";
        String obtained = "assets/share/activity/special/26_8_27/FREE_20_GACHA_OBTAINED.png";
        for (String file : new String[]{selectedCn, selectedGlobal, obtained}) image(root, file);
        write(root, "tasks/activity/assets/assets_activity_special_26_8_27.py", IMPORT
                + "FREE_20_GACHA_SELECTED = ButtonWrapper(name='FREE_20_GACHA_SELECTED', cn="
                + button(selectedCn, "1, 1, 105, 15") + ", global_cn=" + button(selectedGlobal, "1, 1, 25, 15")
                + ", global_en=None)\nFREE_20_GACHA_OBTAINED = ButtonWrapper(name='FREE_20_GACHA_OBTAINED', share="
                + button(obtained, "1, 1, 31, 21") + ")\n");
        write(root, "tasks/activity/free_gacha_20.py",
                "from tasks.activity.assets.assets_activity_special_26_8_27 import FREE_20_GACHA_SELECTED\n"
                        + "result = FREE_20_GACHA_SELECTED\n");
    }

    private static String button(String file, String area) {
        return "Button(file='./" + file + "', area=(" + area + "), search=(0, 0, 1280, 720))";
    }

    private static void write(Path root, String relative, String text) throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
        for (Path parent = file.getParent(); parent != null && !parent.equals(root); parent = parent.getParent()) {
            Path init = parent.resolve("__init__.py");
            if (!Files.exists(init)) Files.writeString(init, "");
        }
    }

    private static void image(Path root, String relative) throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        BufferedImage image = new BufferedImage(1280, 720, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) image.setRGB(x, y, 0xff000000 | (x % 256) << 16 | (y % 256) << 8 | 73);
        }
        ImageIO.write(image, "png", file.toFile());
    }
}
