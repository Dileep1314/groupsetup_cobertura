package groovy.US

public class UploadUtil {
	
	public UploadUtil() {

    }
	private String content;
   private String fileExtension;

   public String getFileExtension() {
           return fileExtension;
    }
  public void setFileExtension(String fileExtension) {
          this.fileExtension = fileExtension;
    }
    public String getContent() {
		return content;
	}
	public void setContent(String content) {
		this.content = content;
	}

	private String fileName;
	
	public String getFileName() {
		return fileName;
	}
	public void setFileName(String fileName) {
		this.fileName = fileName;
	}

}
