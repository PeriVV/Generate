package MyUtils;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;

import java.io.File;

public class Editors {
    public static Document getCurrentDocument(String path){
        VirtualFile fileByRelativePath = LocalFileSystem.getInstance().findFileByIoFile(new File(path));
        Document document = null;
        if(fileByRelativePath!=null)
            document = FileDocumentManager.getInstance().getDocument(fileByRelativePath);
        else{
            System.out.println("fileByRelativePath == null");
        }
        return document;
    }

    public static Editor createSourceEditor(Project project, String path, String language, boolean readOnly) {
        final EditorFactory factory = EditorFactory.getInstance();
        Document document = getCurrentDocument(path);
        if(document==null) return null;
        return factory.createEditor(document, project, FileTypeManager.getInstance()
                .getFileTypeByExtension(language), readOnly);

    }

}
