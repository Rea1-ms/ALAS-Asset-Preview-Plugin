package autoepicseven.preview;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Base64;

import static autoepicseven.preview.AssetPsi.*;

/** Cropped images remain in memory; no generated thumbnails are written beside assets. */
public final class PreviewImages {
    private static final int MAX_FRAMES = 16;
    public static final String IMAGE_PREFIX = "data:image/png;base64,";
    public static final String OPEN_PREFIX = "psi_element://autoepicseven-image/";
    private final Map<String, Image> images = new LinkedHashMap<>(32, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Image> entry) {
            return size() > 128;
        }
    };

    public synchronized Image image(String url) {
        return url != null && url.startsWith(IMAGE_PREFIX) ? images.get(url) : null;
    }

    public synchronized Map<String, Image> imageMap() {
        return Map.copyOf(images);
    }

    public synchronized String render(Definition definition) {
        StringBuilder html = new StringBuilder("<div class='definition'><pre>")
                .append(escape(definition.name())).append("</pre></div><div class='content'>");
        int count = Math.min(MAX_FRAMES, definition.frames().size());
        for (int index = 0; index < count; index++) {
            Frame frame = definition.frames().get(index);
            String label = frame.file();
            if (label != null) {
                label = label.replace('\\', '/');
                if (label.startsWith("./")) label = label.substring(2);
            } else {
                label = frame.namespace();
                if (label == null || label.isEmpty()) label = "素材";
            }
            html.append("<p><b>").append(escape(label)).append("</b>");
            String openLink = null;
            if (frame.file() != null) {
                try {
                    source(definition.root(), frame);
                    openLink = OPEN_PREFIX + index;
                    html.append(" · <a href='").append(openLink).append("'>打开原图</a>");
                } catch (IOException unavailable) {
                    // The preview below explains invalid or missing files. Only
                    // expose navigation for a validated image in this repository.
                }
            }
            html.append("<br>");
            try {
                if (frame.problem() != null) {
                    html.append(escape(frame.problem()));
                } else {
                    BufferedImage crop = crop(definition.root(), frame);
                    ByteArrayOutputStream encoded = new ByteArrayOutputStream();
                    if (!ImageIO.write(crop, "png", encoded)) throw new IOException("无法编码预览图片");
                    // The native AdaptiveImageView parses src before consulting
                    // image callbacks. A private URL scheme fails at that stage;
                    // an embedded PNG uses its supported data-URL renderer.
                    String url = IMAGE_PREFIX + Base64.getEncoder().encodeToString(encoded.toByteArray());
                    images.put(url, crop);
                    double scale = Math.min(3.0, Math.min(420.0 / crop.getWidth(), 200.0 / crop.getHeight()));
                    if (openLink != null) html.append("<a href='").append(openLink).append("'>");
                    html.append("<img src='").append(url).append("' width='")
                            .append(Math.max(1, Math.round(crop.getWidth() * scale))).append("' height='")
                            .append(Math.max(1, Math.round(crop.getHeight() * scale))).append("' alt='点击打开原图'>");
                    if (openLink != null) html.append("</a>");
                    html.append("<br>").append(crop.getWidth()).append(" × ").append(crop.getHeight())
                            .append(" 像素 · 识别区域 (").append(frame.area().left()).append(", ")
                            .append(frame.area().top()).append(", ").append(frame.area().right())
                            .append(", ").append(frame.area().bottom()).append(")");
                }
            } catch (IOException invalid) {
                html.append("预览不可用：").append(escape(invalid.getMessage()));
            }
            html.append("</p>");
        }
        if (definition.frames().size() > count) {
            html.append("<p>仅显示前 ").append(count).append(" 项，共 ")
                    .append(definition.frames().size()).append(" 项；其他帧请查看资产定义。</p>");
        }
        return html.append("</div>").toString();
    }

    public static BufferedImage crop(Path root, Frame frame) throws IOException {
        if (frame.file() == null || frame.area() == null) throw new IOException("缺少图片或识别区域");
        Path source = source(root, frame);
        try (ImageInputStream stream = ImageIO.createImageInputStream(source.toFile())) {
            if (stream == null) throw new IOException("无法读取图片");
            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw new IOException("图片格式无效");
            ImageReader reader = readers.next();
            try {
                reader.setInput(stream);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if ((long) width * height > 16_000_000L) throw new IOException("图片尺寸过大");
                Area area = frame.area();
                if (area.left() < 0 || area.top() < 0 || area.right() > width || area.bottom() > height
                        || area.width() <= 0 || area.height() <= 0) {
                    throw new IOException("识别区域超出图片范围");
                }
                BufferedImage original = reader.read(0);
                // Copy the crop so the cache never retains a full 1280 x 720 canvas.
                BufferedImage crop = new BufferedImage(area.width(), area.height(), BufferedImage.TYPE_INT_ARGB);
                int[] pixels = original.getRGB(area.left(), area.top(), area.width(), area.height(), null, 0, area.width());
                crop.setRGB(0, 0, area.width(), area.height(), pixels, 0, area.width());
                return crop;
            } finally {
                reader.dispose();
            }
        }
    }

    public static Path source(Path root, Frame frame) throws IOException {
        if (frame.file() == null) throw new IOException("缺少图片路径");
        Path assets = root.resolve("assets").toRealPath();
        Path source = root.resolve(frame.file().replace('\\', '/')).normalize();
        if (!source.startsWith(root.resolve("assets").normalize())
                || !source.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".png")) {
            throw new IOException("图片必须位于当前仓库的素材目录");
        }
        if (!Files.isRegularFile(source)) throw new IOException("图片文件不存在");
        source = source.toRealPath();
        if (!source.startsWith(assets)) throw new IOException("图片链接指向素材目录之外");
        return source;
    }

    public static String escape(String value) {
        return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }
}
