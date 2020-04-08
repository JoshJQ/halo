package run.halo.app.handler.file;

import lombok.extern.slf4j.Slf4j;
import net.coobird.thumbnailator.Thumbnails;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.web.multipart.MultipartFile;
import run.halo.app.config.properties.HaloProperties;
import run.halo.app.exception.FileOperationException;
import run.halo.app.exception.ServiceException;
import run.halo.app.model.enums.AttachmentType;
import run.halo.app.model.support.UploadResult;
import run.halo.app.service.OptionService;
import run.halo.app.utils.FilenameUtils;
import run.halo.app.utils.HaloUtils;
import run.halo.app.utils.ImageUtils;

import javax.imageio.ImageIO;
import javax.imageio.ImageWriter;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Calendar;
import java.util.Iterator;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

import static run.halo.app.model.support.HaloConst.FILE_SEPARATOR;

/**
 * Local file handler.
 *
 * @author johnniang
 * @author ryanwang
 * @date 2019-03-27
 */
@Slf4j
@Component
public class LocalFileHandler implements FileHandler {

    /**
     * Upload sub directory.
     */
    private final static String UPLOAD_SUB_DIR = "upload/";

    private final static String THUMBNAIL_SUFFIX = "-thumbnail";

    /**
     * Thumbnail width.
     */
    private final static int THUMB_WIDTH = 256;

    /**
     * Thumbnail height.
     */
    private final static int THUMB_HEIGHT = 256;
    private final OptionService optionService;
    private final String workDir;
    ReentrantLock lock = new ReentrantLock();

    private String fingerPrint;

    public LocalFileHandler(OptionService optionService,
                            HaloProperties haloProperties) {
        this.optionService = optionService;

        // Get work dir
        workDir = FileHandler.normalizeDirectory(haloProperties.getWorkDir());
        fingerPrint = haloProperties.getImageFingerprint();

        // Check work directory
        checkWorkDir();
    }

    /**
     * Check work directory.
     */
    private void checkWorkDir() {
        // Get work path
        Path workPath = Paths.get(workDir);

        // Check file type
        Assert.isTrue(Files.isDirectory(workPath), workDir + " isn't a directory");

        // Check readable
        Assert.isTrue(Files.isReadable(workPath), workDir + " isn't readable");

        // Check writable
        Assert.isTrue(Files.isWritable(workPath), workDir + " isn't writable");
    }

    @Override
    public UploadResult upload(MultipartFile file) {
        Assert.notNull(file, "Multipart file must not be null");

        // Get current time
        Calendar current = Calendar.getInstance(optionService.getLocale());
        // Get month and day of month
        int year = current.get(Calendar.YEAR);
        int month = current.get(Calendar.MONTH) + 1;

        // Build directory
        String subDir = UPLOAD_SUB_DIR + year + FILE_SEPARATOR + month + FILE_SEPARATOR;

        String originalBasename = FilenameUtils.getBasename(file.getOriginalFilename());

        // Get basename
        String basename = originalBasename + '-' + HaloUtils.randomUUIDWithoutDash();

        // Get extension
        String extension = FilenameUtils.getExtension(file.getOriginalFilename());

        log.debug("Base name: [{}], extension: [{}] of original filename: [{}]", basename, extension, file.getOriginalFilename());

        // Build sub file path
        String subFilePath = subDir + basename + '.' + extension;

        // Get upload path
        Path uploadPath = Paths.get(workDir, subFilePath);

        log.info("Uploading to directory: [{}]", uploadPath.toString());

        try {
            // TODO Synchronize here
            // Create directory
            Files.createDirectories(uploadPath.getParent());
            Files.createFile(uploadPath);

            // Upload this file
            file.transferTo(uploadPath);

            // Add finger print
            Path sourcePath = uploadPath;
            subFilePath = subDir + basename + '.' + extension;
            uploadPath = Paths.get(workDir, subFilePath);
            if (!StringUtils.contains(originalBasename, "no_waterprint")) {
                log.info("Adding waterprint to {}", originalBasename);
                addFingerPrint(sourcePath.toFile(), Paths.get(fingerPrint).toFile(), uploadPath.toFile(), 0);
            }

            // Build upload result
            UploadResult uploadResult = new UploadResult();
            uploadResult.setFilename(originalBasename);
            uploadResult.setFilePath(subFilePath);
            uploadResult.setKey(subFilePath);
            uploadResult.setSuffix(extension);
            uploadResult.setMediaType(MediaType.valueOf(Objects.requireNonNull(file.getContentType())));
            uploadResult.setSize(file.getSize());

            // TODO refactor this: if image is svg ext. extension
            boolean isSvg = "svg".equals(extension);

            // Check file type
            if (FileHandler.isImageType(uploadResult.getMediaType()) && !isSvg) {
                lock.lock();
                try {
                    // Upload a thumbnail
                    String thumbnailBasename = basename + THUMBNAIL_SUFFIX;
                    String thumbnailSubFilePath = subDir + thumbnailBasename + '.' + extension;
                    Path thumbnailPath = Paths.get(workDir + thumbnailSubFilePath);

                    // Read as image
                    BufferedImage originalImage = ImageUtils.getImageFromFile(new FileInputStream(uploadPath.toFile()), extension);
                    // Set width and height
                    uploadResult.setWidth(originalImage.getWidth());
                    uploadResult.setHeight(originalImage.getHeight());

                    // Generate thumbnail
                    boolean result = generateThumbnail(originalImage, thumbnailPath, extension);
                    if (result) {
                        // Set thumb path
                        uploadResult.setThumbPath(thumbnailSubFilePath);
                    } else {
                        // If generate error
                        uploadResult.setThumbPath(subFilePath);
                    }
                } finally {
                    lock.unlock();
                }
            } else {
                uploadResult.setThumbPath(subFilePath);
            }

            return uploadResult;
        } catch (IOException e) {
            log.error("Failed to upload file to local: " + uploadPath, e);
            throw new ServiceException("上传附件失败").setErrorData(uploadPath);
        }
    }

    private void addFingerPrint(File srcImageFile, File logoImageFile,
                           File outputImageFile, double degree) throws IOException {
        OutputStream os = null;
        Image srcImg = ImageIO.read(srcImageFile);

        BufferedImage buffImg = new BufferedImage(srcImg.getWidth(null),
                srcImg.getHeight(null), BufferedImage.TYPE_INT_RGB);

        Graphics2D graphics = buffImg.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.drawImage(srcImg.getScaledInstance(srcImg.getWidth(null),
                srcImg.getHeight(null), Image.SCALE_SMOOTH), 0, 0, null);

        ImageIcon logoImgIcon = new ImageIcon(ImageIO.read(logoImageFile));
        Image logoImg = logoImgIcon.getImage();

        //旋转
        if (degree>0) {
            graphics.rotate(Math.toRadians(degree),
                    (double) buffImg.getWidth() / 2,
                    (double) buffImg.getWidth() / 2);
        }

        float alpha = 0.8f; // 透明度
        graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_ATOP, alpha));

        //水印 的位置
        if (buffImg.getWidth() < logoImgIcon.getIconWidth() || buffImg.getHeight() < logoImgIcon.getIconHeight()) {
            int iconWidth = buffImg.getWidth() / 2;
            int iconHeight = buffImg.getHeight() / 2;
            graphics.drawImage(logoImg.getScaledInstance(iconWidth, iconHeight, Image.SCALE_SMOOTH),
                    (buffImg.getWidth() - iconWidth) / 2,
                    (buffImg.getHeight() - iconHeight) / 2, null);
        }
        else
            graphics.drawImage(logoImg, (buffImg.getWidth() - logoImgIcon.getIconWidth())/2,
                    (buffImg.getHeight() - logoImgIcon.getIconHeight())/2, null);
        graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER));
        graphics.dispose();

        os = new FileOutputStream(outputImageFile);
        // 生成图片
        ImageIO.write(buffImg, "JPG", os);
    }

    @Override
    public void delete(String key) {
        Assert.hasText(key, "File key must not be blank");
        // Get path
        Path path = Paths.get(workDir, key);


        // Delete the file key
        try {
            Files.delete(path);
        } catch (IOException e) {
            throw new FileOperationException("附件 " + key + " 删除失败", e);
        }

        // Delete thumb if necessary
        String basename = FilenameUtils.getBasename(key);
        String extension = FilenameUtils.getExtension(key);

        // Get thumbnail name
        String thumbnailName = basename + THUMBNAIL_SUFFIX + '.' + extension;

        // Get thumbnail path
        Path thumbnailPath = Paths.get(path.getParent().toString(), thumbnailName);

        // Delete thumbnail file
        try {
            boolean deleteResult = Files.deleteIfExists(thumbnailPath);
            if (!deleteResult) {
                log.warn("Thumbnail: [{}] may not exist", thumbnailPath.toString());
            }
        } catch (IOException e) {
            throw new FileOperationException("附件缩略图 " + thumbnailName + " 删除失败", e);
        }
    }

    @Override
    public boolean supportType(AttachmentType type) {
        return AttachmentType.LOCAL.equals(type);
    }

    private boolean generateThumbnail(BufferedImage originalImage, Path thumbPath, String extension) {
        Assert.notNull(originalImage, "Image must not be null");
        Assert.notNull(thumbPath, "Thumb path must not be null");


        boolean result = false;
        // Create the thumbnail
        try {
            Files.createFile(thumbPath);
            // Convert to thumbnail and copy the thumbnail
            log.debug("Trying to generate thumbnail: [{}]", thumbPath.toString());
            Thumbnails.of(originalImage).size(THUMB_WIDTH, THUMB_HEIGHT).keepAspectRatio(true).toFile(thumbPath.toFile());
            log.debug("Generated thumbnail image, and wrote the thumbnail to [{}]", thumbPath.toString());
            result = true;
        } catch (Throwable t) {
            log.warn("Failed to generate thumbnail: [{}]", thumbPath);
        }
        return result;
    }
}
