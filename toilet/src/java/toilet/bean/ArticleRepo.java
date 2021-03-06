package toilet.bean;

import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.ejb.ConcurrencyManagement;
import javax.ejb.ConcurrencyManagementType;
import javax.ejb.EJB;
import javax.ejb.LocalBean;
import javax.ejb.Singleton;
import javax.ejb.Startup;
import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.NoResultException;
import javax.persistence.ParameterMode;
import javax.persistence.PersistenceUnit;
import javax.persistence.Query;
import javax.persistence.StoredProcedureQuery;
import javax.persistence.TypedQuery;
import libWebsiteTools.security.HashUtil;
import libWebsiteTools.JVMNotSupportedError;
import libWebsiteTools.db.Repository;
import libWebsiteTools.imead.IMEADHolder;
import toilet.db.Article;
import toilet.db.Comment;
import toilet.db.Section;

/**
 *
 * @author alpha
 */
@Startup
@Singleton
@LocalBean
@ConcurrencyManagement(ConcurrencyManagementType.BEAN)
public class ArticleRepo implements Repository<Article> {

    public static final String DEFAULT_CATEGORY = "entry_defaultCategory";
    private static final Logger LOG = Logger.getLogger(ArticleRepo.class.getName());
    @EJB
    private IMEADHolder imead;
    @PersistenceUnit
    private EntityManagerFactory toiletPU;

    @Override
    public void evict() {
        toiletPU.getCache().evict(Article.class);
    }

    public List<Article> getBySection(String sect, Integer page, Integer perPage) {
        LOG.log(Level.FINE, "Retrieving articles, section {0}, page {1}, per page {2}", new Object[]{sect, page, perPage});
        if (null != sect && sect.equals(imead.getValue(ArticleRepo.DEFAULT_CATEGORY))) {
            sect = null;
        }
        EntityManager em = toiletPU.createEntityManager();
        try {
            TypedQuery<Article> q = sect == null
                    ? em.createNamedQuery("Article.findAll", Article.class)
                    : em.createNamedQuery("Article.findBySection", Article.class).setParameter("section", sect);
            if (page != null && perPage != null) {
                q.setFirstResult(perPage * (page - 1));        // pagination start
                q.setMaxResults(perPage);                      // pagination limit
            }
            return q.getResultList();
        } finally {
            em.close();
        }
    }

    @Override
    public Long count() {
        EntityManager em = toiletPU.createEntityManager();
        try {
            TypedQuery<Long> qn = em.createNamedQuery("Article.count", Long.class);
            Long output = qn.getSingleResult();
            return output;
        } finally {
            em.close();
        }
    }

    @SuppressWarnings("unchecked")
    public List<Article> search(String searchTerm) {
        EntityManager em = toiletPU.createEntityManager();
        try {
            StoredProcedureQuery q = em.createStoredProcedureQuery("toilet.search_articles", Article.class);
            q.registerStoredProcedureParameter(1, String.class, ParameterMode.IN).setParameter(1, searchTerm);
            q.execute();
            return q.getResultList();
        } finally {
            em.close();
        }
    }

    public String searchSuggestion(String searchTerm) {
        EntityManager em = toiletPU.createEntityManager();
        try {
            Query q = em.createNativeQuery("SELECT word, similarity(?1, word) FROM toilet.articlewords WHERE (word % ?1) = TRUE ORDER BY similarity DESC");
            List results = q.setParameter(1, searchTerm).setMaxResults(1).getResultList();
            Object[] row = (Object[]) results.iterator().next();
            Float sim = (Float) row[1];
            if (0.4f < sim && 1.0f > sim) {
                return row[0].toString();
            }
        } catch (NullPointerException n) {
        } finally {
            em.close();
        }
        return null;
    }

    /**
     * will save articles (or just one). articles must be pre-processed before
     * adding.
     *
     * @see ArticleProcessor
     * @param articles map of article to section
     * @return last article saved
     */
    @Override
    public List<Article> upsert(Collection<Article> articles) {
        LOG.log(Level.INFO, "Upserting {0} articles", articles.size());
        Article dbArt;
        ArrayList<Article> out = new ArrayList<>(articles.size());
        EntityManager em = toiletPU.createEntityManager();

        try {
            em.getTransaction().begin();
            for (Article art : articles) {
                String sect = art.getSectionid().getName();
                boolean getnew = art.getArticleid() == null;
                dbArt = getnew ? new Article() : em.find(Article.class, art.getArticleid());
                // TODO: figure out how to upsert
                dbArt.setPosted(art.getPosted() == null ? dbArt.getModified() : art.getPosted());
                dbArt.setComments(art.getComments());
                dbArt.setCommentCollection(art.getComments() ? dbArt.getCommentCollection() : null);
                dbArt.setArticletitle(art.getArticletitle());
                dbArt.setPostedhtml(art.getPostedhtml());
                dbArt.setPostedmarkdown(art.getPostedmarkdown());
                dbArt.setPostedamp(art.getPostedamp());
                dbArt.setPostedname(art.getPostedname());
                dbArt.setDescription(art.getDescription());
                dbArt.setSummary(art.getSummary());
                dbArt.setImageurl(art.getImageurl());
                dbArt.setModified(new Date(new Date().getTime() / 1000 * 1000));

                if (dbArt.getSectionid() == null || !dbArt.getSectionid().getName().equals(sect)) {
                    Section esec;
                    TypedQuery<Section> q = em.createNamedQuery("Section.findByName", Section.class).setParameter("name", sect);
                    try {
                        esec = q.getSingleResult();
                    } catch (NoResultException ex) {
                        esec = new Section();
                        esec.setName(sect);
                        em.persist(esec);
                    }
                    dbArt.setSectionid(esec);
                }
                dbArt.setEtag(HashUtil.getSHA256Hash(hashArticle(dbArt, dbArt.getCommentCollection(), sect)));
                if (getnew) {
                    em.persist(dbArt);
                }
                out.add(dbArt);
                LOG.log(Level.INFO, "Article added {0}, section {1}", new Object[]{art.getArticletitle(), sect});
            }
            em.getTransaction().commit();
            return out;
        } catch (Throwable x) {
            LOG.throwing(ArticleRepo.class.getCanonicalName(), "addArticles", x);
            throw x;
        } finally {
            em.close();
        }
    }

    public void refreshSearch() {
        EntityManager em = toiletPU.createEntityManager();
        em.createStoredProcedureQuery("toilet.refresh_articlesearch").execute();
        em.close();
    }

    @Override
    public Article get(Object articleId) {
        EntityManager em = toiletPU.createEntityManager();
        try {
            return em.find(Article.class, articleId);
        } finally {
            em.close();
        }
    }

    @Override
    public List<Article> getAll(Integer limit) {
        EntityManager em = toiletPU.createEntityManager();
        try {
            TypedQuery<Article> q = em.createNamedQuery("Article.findAll", Article.class);
            if (null != limit) {
                q.setMaxResults(limit);
            }
            return q.getResultList();
        } finally {
            em.close();
        }
    }

    @Override
    public Article delete(Object articleId) {
        EntityManager em = toiletPU.createEntityManager();
        try {
            if (null == articleId) {
                em.getTransaction().begin();
                for (Comment c : em.createNamedQuery("Comment.findAll", Comment.class).getResultList()) {
                    em.remove(c);
                }
                for (Article a : em.createNamedQuery("Article.findAll", Article.class).getResultList()) {
                    em.remove(a);
                }
                for (Section s : em.createNamedQuery("Section.findAll", Section.class).getResultList()) {
                    em.remove(s);
                }
                em.createNativeQuery("ALTER SEQUENCE toilet.comment_commentid_seq RESTART; ALTER SEQUENCE toilet.article_articleid_seq RESTART;").executeUpdate();
                em.createStoredProcedureQuery("toilet.refresh_articlesearch").execute();
                em.getTransaction().commit();
                LOG.info("All articles and comments deleted");
                return null;
            } else {
                Article e = em.find(Article.class, articleId);
                em.getTransaction().begin();
                if (e.getSectionid().getArticleCollection().size() == 1) {
                    em.remove(e);
                    em.remove(e.getSectionid());
                } else {
                    em.remove(e);
                }
                em.getTransaction().commit();
                LOG.info("Article deleted");
                return e;
            }
        } finally {
            em.close();
        }
    }

    public static void updateArticleHash(EntityManager em, Integer articleId) {
        Article art = em.find(Article.class, articleId);
        art.setEtag(Base64.getEncoder().encodeToString(hashArticle(art, art.getCommentCollection(), art.getSectionid().getName())));
        art.setModified(new Date(new Date().getTime() / 1000 * 1000));
    }

    /**
     * process all articles one by one
     *
     * @param operation
     */
    @Override
    public void processArchive(Consumer<Article> operation, Boolean transaction) {
        EntityManager em = toiletPU.createEntityManager();
        try {
            if (transaction) {
                em.getTransaction().begin();
                em.createNamedQuery("Article.findAll", Article.class).getResultStream().forEachOrdered(operation);
                em.getTransaction().commit();
            } else {
                em.createNamedQuery("Article.findAll", Article.class).getResultStream().forEachOrdered(operation);
            }
        } finally {
            em.close();
        }
    }

    private static byte[] hashArticle(Article e, Collection<Comment> comments, String sect) {
        try {
            MessageDigest sha = HashUtil.getSHA256();
            sha.update(e.getArticletitle().getBytes("UTF-8"));
            sha.update(e.getPostedhtml().getBytes("UTF-8"));
            sha.update(sect.getBytes("UTF-8"));
            sha.update(e.getPostedname().getBytes("UTF-8"));
            if (e.getDescription() != null) {
                sha.update(e.getDescription().getBytes("UTF-8"));
            }
            sha.update(e.getModified().toString().getBytes("UTF-8"));
            if (comments != null) {
                for (Comment c : comments) {
                    sha.update(c.getPostedhtml().getBytes("UTF-8"));
                    sha.update(c.getPosted().toString().getBytes("UTF-8"));
                    sha.update(c.getPostedname().getBytes("UTF-8"));
                }
            }
            return sha.digest();
        } catch (UnsupportedEncodingException enc) {
            throw new JVMNotSupportedError(enc);
        }
    }
}
