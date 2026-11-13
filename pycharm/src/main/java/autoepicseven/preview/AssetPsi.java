package autoepicseven.preview;

import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import com.jetbrains.python.psi.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Static PSI inspection only: asset modules must never be imported or executed. */
public final class AssetPsi {
    private AssetPsi() {}

    public record Area(int left, int top, int right, int bottom) {
        public int width() { return right - left; }
        public int height() { return bottom - top; }
    }

    public record Frame(String namespace, String file, Area area, String problem) {}
    public record Definition(String name, Path root, List<Frame> frames) {}

    public static PyTargetExpression declaration(PsiElement element) {
        Set<PsiElement> visited = new HashSet<>();
        PsiElement current = element;
        for (int depth = 0; current != null && depth < 16 && visited.add(current); depth++) {
            if (current instanceof PyImportElement imported) {
                current = imported.resolve();
            } else if (current instanceof PyReferenceExpression reference) {
                current = reference.getReference().resolve();
            } else if (current instanceof PyTargetExpression target) {
                PyExpression value = target.findAssignedValue();
                if (value instanceof PyCallExpression call && call.isCalleeText("ButtonWrapper", "Button")) {
                    return target;
                }
                if (value instanceof PyReferenceExpression) {
                    current = value;
                } else {
                    current = PsiTreeUtil.getParentOfType(target, PyImportElement.class);
                }
            } else {
                current = PsiTreeUtil.getParentOfType(current,
                        PyReferenceExpression.class, PyTargetExpression.class, PyImportElement.class);
            }
        }
        return null;
    }

    public static Definition definition(PsiElement element) {
        PyTargetExpression target = declaration(element);
        if (target == null || target.getContainingFile() == null) return null;
        VirtualFile file = target.getContainingFile().getVirtualFile();
        if (file == null || !file.isInLocalFileSystem()) return null;
        // Resolve from the declaration, not the active project: a window can contain
        // several repositories or worktrees with identical asset variable names.
        for (Path parent = Path.of(file.getPath()).getParent(); parent != null; parent = parent.getParent()) {
            if (Files.isRegularFile(parent.resolve("module/base/button.py"))
                    && Files.isDirectory(parent.resolve("assets"))) {
                return parse(target, parent);
            }
        }
        return null;
    }

    public static Definition parse(PyTargetExpression target, Path root) {
        if (!(target.findAssignedValue() instanceof PyCallExpression call)) return null;
        List<Frame> frames = new ArrayList<>();
        if (call.isCalleeText("ButtonWrapper")) {
            for (PyExpression argument : call.getArguments()) {
                if (argument instanceof PyKeywordArgument keyword && !"name".equals(keyword.getName())) {
                    collect(keyword.getName(), keyword.getValueExpression(), frames);
                }
            }
        } else if (call.isCalleeText("Button")) {
            collect("", call, frames);
        }
        return frames.isEmpty() ? null : new Definition(target.getName(), root, List.copyOf(frames));
    }

    private static void collect(String namespace, PyExpression value, List<Frame> frames) {
        while (value instanceof PyParenthesizedExpression parentheses) {
            value = parentheses.getContainedExpression();
        }
        if (value instanceof PySequenceExpression sequence) {
            for (PyExpression child : sequence.getElements()) collect(namespace, child, frames);
        } else if (value instanceof PyCallExpression call && call.isCalleeText("Button")) {
            PyExpression fileValue = call.getKeywordArgument("file");
            String file = fileValue instanceof PyStringLiteralExpression literal && !literal.isInterpolated()
                    ? literal.getStringValue() : null;
            Area area = area(call.getKeywordArgument("area"));
            String problem = file == null ? "无法读取静态图片路径"
                    : area == null ? "无法读取静态识别区域" : null;
            frames.add(new Frame(namespace, file, area, problem));
        } else if (value != null && "None".equals(value.getText())) {
            frames.add(new Frame(namespace, null, null, "此版本没有素材"));
        }
    }

    private static Area area(PyExpression expression) {
        while (expression instanceof PyParenthesizedExpression parentheses) {
            expression = parentheses.getContainedExpression();
        }
        if (!(expression instanceof PySequenceExpression tuple) || tuple.getElements().length != 4) return null;
        int[] numbers = new int[4];
        for (int index = 0; index < 4; index++) {
            String text = tuple.getElements()[index].getText();
            if (!text.matches("-?[0-9]+")) return null;
            try {
                numbers[index] = Integer.parseInt(text);
            } catch (NumberFormatException invalid) {
                return null;
            }
        }
        return new Area(numbers[0], numbers[1], numbers[2], numbers[3]);
    }
}
