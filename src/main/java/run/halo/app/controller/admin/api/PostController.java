package run.halo.app.controller.admin.api;

import cn.hutool.core.util.IdUtil;
import freemarker.template.Configuration;
import freemarker.template.Template;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.SortDefault;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.view.freemarker.FreeMarkerConfigurer;
import run.halo.app.cache.StringCacheStore;
import run.halo.app.model.dto.post.BasePostMinimalDTO;
import run.halo.app.model.dto.post.BasePostSimpleDTO;
import run.halo.app.model.entity.Category;
import run.halo.app.model.entity.Post;
import run.halo.app.model.entity.Tag;
import run.halo.app.model.enums.PostStatus;
import run.halo.app.model.params.PostParam;
import run.halo.app.model.params.PostQuery;
import run.halo.app.model.vo.BaseCommentVO;
import run.halo.app.model.vo.PostDetailVO;
import run.halo.app.model.vo.PostListVO;
import run.halo.app.service.*;
import run.halo.app.utils.FreeMarkerUtil;
import run.halo.app.utils.MarkdownUtils;

import javax.validation.Valid;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.springframework.data.domain.Sort.Direction.DESC;

/**
 * Post controller.
 *
 * @author johnniang
 * @author ryanwang
 * @date 3/19/19
 */
@RestController
@RequestMapping("/api/admin/posts")
public class PostController {

    private final PostService postService;

    private final StringCacheStore cacheStore;

    private final OptionService optionService;

    private final PostCategoryService postCategoryService;

    private final PostTagService postTagService;

    private final PostCommentService postCommentService;

    public PostController(PostService postService,
                          StringCacheStore cacheStore,
                          OptionService optionService,
                          PostCategoryService postCategoryService,
                          PostTagService postTagService,
                          PostCommentService postCommentService) {
        this.postService = postService;
        this.cacheStore = cacheStore;
        this.optionService = optionService;
        this.postCategoryService = postCategoryService;
        this.postTagService = postTagService;
        this.postCommentService = postCommentService;
    }
    /*public PostController(PostService postService,
                          StringCacheStore cacheStore,
                          OptionService optionService) {
        this.postService = postService;
        this.cacheStore = cacheStore;
        this.optionService = optionService;
    }*/
    @Autowired
    private FreeMarkerConfigurer freeMarkerConfigurer;

    @GetMapping
    @ApiOperation("Lists posts")
    public Page<PostListVO> pageBy(Integer page, Integer size,
                                   @SortDefault.SortDefaults({
                                           @SortDefault(sort = "topPriority", direction = DESC),
                                           @SortDefault(sort = "createTime", direction = DESC)
                                   }) Sort sort,
                                   PostQuery postQuery) {
        Pageable pageable = PageRequest.of(page, size, sort);
        Page<Post> postPage = postService.pageBy(postQuery, pageable);
        return postService.convertToListVo(postPage);
    }

    @GetMapping("latest")
    @ApiOperation("Pages latest post")
    public List<BasePostMinimalDTO> pageLatest(@RequestParam(name = "top", defaultValue = "10") int top) {
        return postService.convertToMinimal(postService.pageLatest(top).getContent());
    }

    @GetMapping("status/{status}")
    @ApiOperation("Gets a page of post by post status")
    public Page<? extends BasePostSimpleDTO> pageByStatus(@PathVariable(name = "status") PostStatus status,
                                                          @RequestParam(value = "more", required = false, defaultValue = "false") Boolean more,
                                                          @PageableDefault(sort = "createTime", direction = DESC) Pageable pageable) {
        Page<Post> posts = postService.pageBy(status, pageable);

        if (more) {
            return postService.convertToListVo(posts);
        }

        return postService.convertToSimple(posts);
    }

    @GetMapping("{postId:\\d+}")
    public PostDetailVO getBy(@PathVariable("postId") Integer postId) {
        Post post = postService.getById(postId);
        return postService.convertToDetailVo(post);
    }

    @PutMapping("{postId:\\d+}/likes")
    @ApiOperation("Likes a post")
    public void likes(@PathVariable("postId") Integer postId) {
        postService.increaseLike(postId);
    }

    @PostMapping
    public PostDetailVO createBy(@Valid @RequestBody PostParam postParam,
        @RequestParam(value = "autoSave", required = false, defaultValue = "false") Boolean autoSave,Model model) throws Exception{
        // Convert to
        Post post = postParam.convertTo();

        Date date = new Date();
        SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyyMMddHHssmm");
        String format = simpleDateFormat.format(date);
        String postName = format+".html";
        post.setUrl(postName);
        String originalContent = post.getOriginalContent();
        post.setOriginalContent("");

        PostDetailVO by = postService.createBy(post, postParam.getTagIds(), postParam.getCategoryIds(), autoSave);

        if (post.getStatus() == PostStatus.PUBLISHED) {
            post.setFormatContent(MarkdownUtils.renderHtml(originalContent));
        }

        postService.getNextPost(post.getCreateTime()).ifPresent(nextPost -> model.addAttribute("nextPost", nextPost));
        postService.getPrePost(post.getCreateTime()).ifPresent(prePost -> model.addAttribute("prePost", prePost));

        List<Category> categories = postCategoryService.listCategoriesBy(post.getId());
        List<Tag> tags = postTagService.listTagsBy(post.getId());

        //Page<BaseCommentVO> comments = postCommentService.pageVosBy(post.getId(), PageRequest.of(cp, optionService.getCommentPageSize(), sort));

        model.addAttribute("is_post", true);
        model.addAttribute("post", postService.convertToDetailVo(post));
        model.addAttribute("categories", categories);
        model.addAttribute("tags", tags);
        //model.addAttribute("comments", comments);

        FreeMarkerUtil.createHtml(freeMarkerConfigurer, "themes/anatole/post.ftl",model, "F:/nginx-1.12.2/haloBlog/html", postName);//根据模板生成静态页面

        return by;
    }

    @PutMapping("{postId:\\d+}")
    public PostDetailVO updateBy(@Valid @RequestBody PostParam postParam,
                                 @PathVariable("postId") Integer postId,
                                 @RequestParam(value = "autoSave", required = false, defaultValue = "false") Boolean autoSave) {
        // Get the post info
        Post postToUpdate = postService.getById(postId);

        postParam.update(postToUpdate);

        return postService.updateBy(postToUpdate, postParam.getTagIds(), postParam.getCategoryIds(), autoSave);
    }

    @PutMapping("{postId:\\d+}/status/{status}")
    public void updateStatusBy(
            @PathVariable("postId") Integer postId,
            @PathVariable("status") PostStatus status) {
        Post post = postService.getById(postId);

        // Set status
        post.setStatus(status);

        // Update
        postService.update(post);
    }

    @DeleteMapping("{postId:\\d+}")
    public void deletePermanently(@PathVariable("postId") Integer postId) {
        // Remove it
        postService.removeById(postId);
    }

    @GetMapping("preview/{postId:\\d+}")
    public String preview(@PathVariable("postId") Integer postId) {
        Post post = postService.getById(postId);

        String token = IdUtil.simpleUUID();

        // cache preview token
        cacheStore.putAny("preview-post-token-" + postId, token, 10, TimeUnit.MINUTES);

        // build preview post url and return
        return String.format("%s/archives/%s?preview=true&token=%s", optionService.getBlogBaseUrl(), post.getUrl(), token);
    }
}
