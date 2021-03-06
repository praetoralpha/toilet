package toilet;

import toilet.bean.ToiletBeanAccess;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;
import libWebsiteTools.security.HashUtil;
import libWebsiteTools.file.FileUtil;
import libWebsiteTools.file.Fileupload;
import libWebsiteTools.imead.Localization;
import toilet.servlet.AdminLoginServlet;

/**
 * Does just enough bootstrapping for a first time setup.
 *
 * @author alpha
 */
@WebListener
public class FirstTimeDetector extends ToiletBeanAccess implements ServletContextListener {

    public static final String FIRST_TIME_SETUP = "FIRST_TIME_SETUP";
    private static final Logger LOG = Logger.getLogger(FirstTimeDetector.class.getName());

    public static boolean isFirstTime(AllBeanAccess beans) {
        return null == beans.getImeadValue(AdminLoginServlet.IMEAD)
                || !HashUtil.ARGON2_ENCODING_PATTERN.matcher(beans.getImeadValue(AdminLoginServlet.IMEAD)).matches();
    }

    @Override
    public void contextInitialized(ServletContextEvent sce) {
        List<Localization> locals = new ArrayList<>();
        // in a new release, some properties may be added
        try {
            locals.addAll(getNewLocalizations(sce.getServletContext(), "/WEB-INF/IMEAD.properties", Locale.ROOT));
            locals.addAll(getNewLocalizations(sce.getServletContext(), "/WEB-INF/IMEAD-en.properties", Locale.ENGLISH));
        } catch (IOException ex) {
            LOG.log(Level.SEVERE, null, ex);
        }
        if (isFirstTime(this)) {
            sce.getServletContext().setAttribute(FIRST_TIME_SETUP, FIRST_TIME_SETUP);
            getArts().refreshSearch();
            // load and save default files
            try {
                loadFile(sce.getServletContext(), "/WEB-INF/toiletwave.css", "text/css");
                loadFile(sce.getServletContext(), "/WEB-INF/toiletwave.js", "text/javascript");
                if (null == getImeadValue("site_css")) {
                    locals.add(new Localization("", "site_css", "toiletwave.css"));
                }
                if (null == getImeadValue("site_cssamp")) {
                    locals.add(new Localization("", "site_cssamp", "toiletwave.css"));
                }
                if (null == getImeadValue("site_javascript")) {
                    locals.add(new Localization("", "site_javascript", "toiletwave.js"));
                }
            } catch (IOException ex) {
                LOG.log(Level.SEVERE, null, ex);
            }
        }
        getImead().upsert(locals);
    }

    /**
     * reads a properties file from deployed WAR, and returns Localization
     * objects that aren't already loaded. this will not return Localizations
     * that are changed; only ones that aren't already present.
     *
     * @param c
     * @param filename
     * @param locale
     * @return
     * @throws IOException
     */
    private List<Localization> getNewLocalizations(ServletContext c, String filename, Locale locale) throws IOException {
        List<Localization> locals = new ArrayList<>();
        Properties IMEAD = getProperties(c.getResourceAsStream(filename));
        for (Map.Entry<Object, Object> property : IMEAD.entrySet()) {
            try {
                getImead().getLocal(property.getKey().toString(), locale.toLanguageTag());
            } catch (RuntimeException r) {
                locals.add(new Localization(locale.toString(), property.getKey().toString(), property.getValue().toString()));
            }
        }
        return locals;
    }

    /**
     * reads a properties file from deployed WAR, and returns a properties
     * object
     *
     * @param file
     * @return
     * @throws IOException
     */
    private Properties getProperties(InputStream file) throws IOException {
        Properties IMEAD = new Properties();
        IMEAD.load(file);
        return IMEAD;
    }

    private void loadFile(ServletContext c, String filename, String type) throws IOException {
        String servedName = filename.substring("/WEB-INF/".length());
        if (null == getFile().get(servedName)) {
            Fileupload cssFile = new Fileupload();
            cssFile.setAtime(new Date());
            cssFile.setFiledata(FileUtil.getByteArray(c.getResourceAsStream(filename)));
            cssFile.setEtag(HashUtil.getSHA256Hash(cssFile.getFiledata()));
            cssFile.setFilename(servedName);
            cssFile.setMimetype(type);
            getFile().upsert(Arrays.asList(cssFile));
        }
    }

    @Override
    public void contextDestroyed(ServletContextEvent sce) {
    }
}
