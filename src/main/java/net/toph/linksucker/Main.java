package net.toph.linksucker;

import org.apache.http.NameValuePair;
import org.apache.http.client.fluent.Content;
import org.apache.http.client.fluent.Request;
import org.apache.http.message.BasicNameValuePair;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Tag;
import org.jsoup.select.Elements;

import java.io.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Created by ckovacs on 12/31/15.
 */
public class Main {

    public static void main(String[] args) {

        File threadsFile = new File("threads.csv");
        File postsFile = new File("posts.csv");
        if (threadsFile.exists()) {
            threadsFile.delete();
        }
        if(postsFile.exists()) {
            postsFile.delete();
        }

        System.out.println("Writing threads file " + threadsFile.getAbsolutePath());
        System.out.println("Writing posts file " + postsFile.getAbsolutePath());

        try (
                PrintStream threadsOut = new PrintStream(new FileOutputStream(threadsFile));
                PrintStream postsOut = new PrintStream(new FileOutputStream(postsFile));

        ) {

//            System.setOut(logOut);
//            System.setErr(logOut);
            // Log in to POA
            System.out.println("Logging in");
            List<NameValuePair> postVars = new ArrayList<>();
            postVars.add(new BasicNameValuePair("vb_login_username", "ChrisK"));
            postVars.add(new BasicNameValuePair("vb_login_password", ""));
            postVars.add(new BasicNameValuePair("s", ""));
            postVars.add(new BasicNameValuePair("securitytoken", "guest"));
            postVars.add(new BasicNameValuePair("do", "login"));
            postVars.add(new BasicNameValuePair("vb_login_md5password", "yeahright"));
            postVars.add(new BasicNameValuePair("vb_login_md5password_utf", "likeimpostingthathere"));
            Request.Post("http://www.pilotsofamerica.com/forum/login.php?do=login")
                    .bodyForm(postVars)
                    .execute()
                    .returnResponse();

            // Visit spin zone
            Document doc = parse("http://www.pilotsofamerica.com/forum/forumdisplay.php?f=34");

            // Get list of pages from spin zone
            Elements lastPageElements = doc.getElementsByAttributeValueStarting("title", "Last Page - Results");
            if(lastPageElements.size() <= 0) {
                System.err.println("Sorry charlie - Spin Zone is closed, or you don't know how to log in!");
                System.exit(-1);
            }
            Element lastPageElement = lastPageElements.get(0);
            String[] postCountArray = lastPageElement.attr("title").split(" ");
            String[] pageQueryString = lastPageElement.attr("href").split("&");
            String[] pageNvp = pageQueryString[pageQueryString.length - 1].split("=");
            int lastPage = intOrElse(pageNvp[1]);
            String postCount = postCountArray[postCountArray.length - 1];
            System.out.println(lastPage + " pages in spin zone.");
            System.out.println(postCount + " total posts.");

            // Get sticky threads
            System.out.println("Parsing sticky threads");
            Elements threads = doc.getElementsByAttributeValueStarting("id", "thread_title_");
            for (Element thread : threads) {
                if (!thread.previousSibling().toString().trim().equals("Sticky:")) {
                    continue;
                }
                parseThread(thread, true, threadsOut, postsOut);
            }

            // Get list of threads for each page
            for (int i = 1; i < lastPage; i++) {
                System.out.println("Parsing page " + i + "/" + lastPage + " (" + Math.round(100 * i / lastPage) + "%)");
                doc = parse("http://www.pilotsofamerica.com/forum/forumdisplay.php?f=34&order=desc&page=" + i);
                threads = doc.getElementsByAttributeValueStarting("id", "thread_title_");
                for (Element thread : threads) {
                    if (thread.previousSibling().toString().trim().equals("Sticky:")) {
                        continue;
                    }
                    parseThread(thread, false, threadsOut, postsOut);
                }
            }


        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    public static Document parse(String url) throws IOException, InterruptedException {
        System.out.println("Loading " + url);
        Content content = null;
        int retry = 0;
        while(retry++ < 100) {
            try {
                content = Request.Get(url).execute().returnContent();
            } catch (Exception e) {
                e.printStackTrace();
                Thread.sleep(500);
            }
        }
        return Jsoup.parse(content.asString());
    }

    public static void parseThread(Element thread, boolean sticky, PrintStream os, PrintStream postsOut) throws IOException, InterruptedException {
        String threadHref = thread.attr("href");
        Element threadRow = thread.parent().parent().parent();
        Element memberElement = threadRow.getElementsByAttributeValueStarting("onclick", "window.open('member.php?u=").first();
        String startedByName = memberElement.text();
        int startedById = intOrElse(memberElement.attr("onclick").replaceAll("window\\.open\\('member\\.php\\?u=", "").replaceAll("', '_self'\\)", ""));
        int views = intOrElse(threadRow.getElementsByTag("td").last().text().replaceAll(",", ""));
        String title = thread.text();
        int threadId = intOrElse(threadHref.split("=")[1]);
        os.println(String.format("%s, %s,%s,%s,%s,%s", sticky, quoteEscapeFlatten(title), threadId, quoteEscapeFlatten(startedByName), startedById, views));

        // Get list of pages for each thread
        String baseLink = "http://www.pilotsofamerica.com/forum/showthread.php?t=" + threadId;
        String link = baseLink;
        int i = 1;
        boolean hasMore = false;
        do {
            Document doc = parse(link);
            hasMore = doc.getElementsByAttributeValueStarting("href", "showthread.php?t=" + threadId + "&page=" + ++i).first() != null;
            Elements posts = doc.getElementsByAttributeValueStarting("id", "post").stream().filter(element ->
                    "table".equals(element.tagName())
            ).collect(Collectors.toCollection(Elements::new));
            System.out.println("Parsing " + posts.size() + " posts on page " + (i - 1) + ".");
            for (Element post : posts) {
                parsePost(threadId, post, postsOut);
            }
            link = baseLink + "&page=" + i;
        } while (hasMore);
        // Get each page
    }

    private static void parsePost(int threadId, Element post, PrintStream out) {
        String userName = Optional.ofNullable(post.getElementsMatchingOwnText("\\(User ID: ").first()).orElse(getUserLink(post)).text().replaceAll("^\\(User ID: ","").replaceAll("\\)$","");
        int userId = intOrElse(getUserLink(post).attr("href").split("=")[1]);
        int postId = intOrElse(post.id().replaceAll("post",""));
        String postDate = "";
        String postHtml = post.getElementById("post_message_" + postId).html();
        postDate = post.getElementsByAttributeValue("name", post.id()).stream().filter(element -> "a".equals(element.tagName())).findFirst().get().nextSibling().toString().trim();
        postDate = postDate.replaceAll("Today", LocalDate.now().format(DateTimeFormatter.ISO_DATE));
        out.println(String.format("%s,%s,%s,%s,%s,%s",threadId, postId, quoteEscapeFlatten(userName), userId, postDate, quoteEscapeFlatten(postHtml)));

    }

    private static String quoteEscapeFlatten(String s) {
        if(s == null) {
            return s;
        }
        return "\"" + s.replaceAll("\"","\\\\\"").replaceAll("\n","\\\\n") + "\"";
    }

    private static int intOrElse(String s) {
        int orElse = 0;
        try {
            orElse = Integer.parseInt(s);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return orElse;
    }

    private static Element getUserLink(Element post) {
        return Optional.ofNullable(post.getElementsByClass("bigusername").first()).orElse(blankUserLink());
    }

    private static Element blankUserLink() {
        Element a = new Element(Tag.valueOf("a"), "");
        a.attr("href", "id=0");
        return a;
    }

}
