package autoepicseven.preview;

import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static autoepicseven.preview.AssetPsi.*;

/** Standalone Java checks: no game runtime, IDE installation changes, or device access. */
public final class PreviewChecks {
    private static int checks;

    public static void main(String[] arguments) throws Exception {
        Path root = Path.of(arguments[0]);
        Frame first = new Frame("cn", "./assets/cn/base/page/MAIN_GOTO_SECRET_SHOP.png",
                new Area(26, 279, 39, 292), null);
        Frame second = new Frame("cn", "./assets/cn/base/page/MAIN_GOTO_SECRET_SHOP.2.png",
                new Area(26, 357, 39, 370), null);
        BufferedImage crop = PreviewImages.crop(root, first);
        check(crop.getWidth() == 13 && crop.getHeight() == 13, "Crop dimensions match the generated area");
        BufferedImage original = javax.imageio.ImageIO.read(root.resolve(first.file()).toFile());
        check(crop.getRGB(5, 5) == original.getRGB(31, 284), "Crop pixels come from the recognition area");
        PreviewImages previews = new PreviewImages();
        String html = previews.render(new Definition("MAIN_GOTO_SECRET_SHOP", root, List.of(first, second)));
        check(html.contains("MAIN_GOTO_SECRET_SHOP.2.png"), "All template frames appear");
        Matcher images = Pattern.compile("src='([^']+)'").matcher(html);
        int imageCount = 0;
        while (images.find()) {
            Image image = previews.image(images.group(1));
            check(image != null && image.getWidth(null) == 13 && image.getHeight(null) == 13,
                    "Documentation images resolve through the local image callback");
            String embedded = images.group(1);
            check(embedded.startsWith(PreviewImages.IMAGE_PREFIX), "Images use the native embedded PNG scheme");
            BufferedImage decoded = javax.imageio.ImageIO.read(new ByteArrayInputStream(
                    Base64.getDecoder().decode(embedded.substring(PreviewImages.IMAGE_PREFIX.length()))));
            check(decoded != null && decoded.getRGB(5, 5) == ((BufferedImage) image).getRGB(5, 5),
                    "The embedded PNG preserves the cropped image pixels");
            String link = PreviewImages.OPEN_PREFIX + imageCount;
            check(html.contains("href='" + link + "'>打开原图</a>")
                            && html.contains("href='" + link + "'><img"),
                    "Each source action and clickable image target the same frame");
            imageCount++;
        }
        check(imageCount == 2, "One image per frame");
        check(previews.image("https://example.invalid/image.png") == null, "Foreign URLs are not resolved");
        expectFailure(root, new Frame("share", first.file(), new Area(-1, 0, 20, 20), null));
        expectFailure(root, new Frame("share", first.file(), new Area(0, 0, 2000, 720), null));
        expectFailure(root, new Frame("share", "./module/base/button.py", first.area(), null));
        expectFailure(root, new Frame("share", "./assets/../module/base/button.py", first.area(), null));
        expectFailure(root, new Frame("share", "./assets/share/DOES_NOT_EXIST.png", first.area(), null));
        String missing = previews.render(new Definition("<unsafe>", root, List.of(
                new Frame("cn", null, null, "此版本没有素材"))));
        check(missing.contains("&lt;unsafe&gt;") && !missing.contains("<unsafe>"), "Names are HTML escaped");
        check(missing.contains("<b>cn</b>") && missing.contains("此版本没有素材"),
                "Unavailable variants retain the original namespace without assigning a server meaning");
        System.out.println("PASS: " + checks + " image/documentation checks");
    }

    private static void expectFailure(Path root, Frame frame) throws Exception {
        try {
            PreviewImages.crop(root, frame);
            throw new AssertionError("Expected an invalid path or crop to fail: " + frame);
        } catch (IOException expected) {
            checks++;
        }
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
        checks++;
    }
}
