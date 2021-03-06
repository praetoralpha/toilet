package libWebsiteTools.tag;

public class FileSelect extends AbstractInput {

    private String accept;

    @Override
    protected StringBuilder createIncompleteTag() {
        StringBuilder out = super.createIncompleteTag();
        if (null != accept) {
            out.append(" accept=\"").append(accept).append("\"");
        }
        return out;
    }

    @Override
    public String getType() {
        return "file";
    }

    /**
     * @param accept the accept to set
     */
    public void setAccept(String accept) {
        this.accept = accept;
    }
}
