package org.xnatural.enet.test.service;

import org.apache.commons.io.IOUtils;
import org.xnatural.enet.event.EL;
import org.xnatural.enet.server.ServerTpl;
import org.xnatural.enet.test.rest.FileData;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import static java.util.Collections.emptyList;
import static org.xnatural.enet.common.Utils.isEmpty;

/**
 * @author xiangxb, 2018-10-14
 */
public class FileUploader extends ServerTpl {

    /**
     * 文件上传的 本地保存目录
     */
    private String localDir;
    /**
     * 文件上传 的访问url前缀. 默认为 /file/**
     */
    private URI    urlPrefix;


    public FileUploader() {
        setName("file-uploader");
    }


    @EL(name = "sys.starting")
    protected void init() {
        try {
            localDir = getStr("local-dir", new URL("file:upload").getFile());
            File dir = new File(localDir);
            dir.mkdirs();
            log.info("file upload local dir: {}", dir.getAbsolutePath());

            String hostname = coreEp.fire("http.getHostname").toString();
            urlPrefix = URI.create(getStr("url-prefix", ("http://" + (isEmpty(hostname) ? "localhost" : hostname) + ":" + coreEp.fire("http.getPort") + "/file/")) + "/");
            log.info("static file url prefix: {}", urlPrefix);
        } catch (MalformedURLException e) {
            log.error(e);
        }
    }


    /**
     *  例: 文件名为 aa.txt, 返回: arr[0]=aa, arr[1]=txt
     * @param fileName
     * @return
     */
    public String[] extractFileName(String fileName) {
        if (fileName == null || fileName.isEmpty()) return new String[]{null, null};
        int i = fileName.lastIndexOf(".");
        if (i == -1) return new String[]{fileName, null};
        return new String[]{fileName.substring(0, i), fileName.substring(i + 1)};
    }


    /**
     * 映射 文件名 到一个 url
     * @param fileName
     * @return
     */
    public String toFullUrl(String fileName) {
        return urlPrefix.resolve(fileName).toString();
    }


    /**
     * 查找文件
     * @param fileName
     * @return
     */
    public File findFile(String fileName) {
        return new File(localDir + File.separator + fileName);
    }


    /**
     * 多文件 多线程保存
     * @param files
     */
    public void save(FileData... files) {
        if (files == null || files.length == 0) return;
        // 并发上传
        if (files.length >= 2) {
            List<Callable<FileData>> uploadPayload = Arrays.stream(files).skip(1).filter(f -> f != null)
                    .map(f -> new Callable<FileData>() {
                        @Override
                        public FileData call() throws Exception {
                            try (FileOutputStream fo = new FileOutputStream(new File(localDir + File.separator + f.getResultName()));
                                 InputStream in = f.getInputStream()) {
                                IOUtils.copy(in, fo);
                            } catch (Exception ex) {
                                log.error(ex);
                            }
                            return f;
                        }
                    }).collect(Collectors.toList());

            ExecutorService executor = null;
            try {
                List<Future<FileData>> fs = emptyList();
                if (!uploadPayload.isEmpty()) {
                    executor = Executors.newFixedThreadPool(Math.min(files.length - 1, 5));
                    fs = executor.invokeAll(uploadPayload);
                }

                // 第一个用当前线程执行, 其余的用 executor 执行
                FileData f = files[0];
                if (f != null) {
                    try (FileOutputStream fo = new FileOutputStream(new File(localDir + File.separator + f.getResultName()));
                         InputStream in = f.getInputStream()) {
                        IOUtils.copy(in, fo);
                    } catch (Exception ex) {
                        log.error(ex);
                    }
                }

                // 此处会等待线程池中所有任务执行完
                for (Future<FileData> fu : fs) fu.get();
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                if (executor != null) executor.shutdown();
            }
        } else if (files.length == 1){
            FileData f = files[0];
            if (f == null) return;
            try (FileOutputStream fo = new FileOutputStream(new File(localDir + File.separator + f.getResultName()));
                 InputStream in = f.getInputStream()) {
                IOUtils.copy(in, fo);
            } catch (Exception ex) {
                log.error(ex);
            }
        }
    }
}
