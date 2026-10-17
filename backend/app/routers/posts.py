from fastapi import APIRouter, Depends, File, Form, HTTPException, Request, UploadFile, status
from sqlalchemy.orm import Session

from app.core.database import get_db
from app.core.deps import get_current_user, require_institution_scope
from app.core.limiter import limiter
from app.models.post import Post
from app.models.post_comment import PostComment
from app.models.post_report import PostReport
from app.models.user import User, UserRole
from app.schemas.post import PostCommentCreate, PostCommentOut, PostOut, PostReportCreate, PostReportOut
from app.utils.uploads import save_post_image, delete_storage_object, read_upload

router = APIRouter(prefix="/api/posts", tags=["posts"])


@router.post("", response_model=PostOut, status_code=status.HTTP_201_CREATED)
@limiter.limit("10/minute")
def create_post(
    request: Request,
    title: str = Form(...),
    body: str = Form(...),
    image: UploadFile | None = File(None),
    db: Session = Depends(get_db),
    current_user: User = Depends(require_institution_scope),
):
    """
    Accepts multipart/form-data (rather than a JSON body) so an optional
    photo can be attached to the post — images only, no video.
    """
    if current_user.role != UserRole.STAFF:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Only staff post news")

    title = title.strip()
    body = body.strip()
    if not title or not body:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="title and body are required")

    image_path = None
    if image is not None and image.filename:
        contents = read_upload(image)
        image_path = save_post_image(image, contents)

    post = Post(
        institution_id=current_user.institution_id,
        author_id=current_user.id,
        title=title,
        body=body,
        image_path=image_path,
    )
    db.add(post)
    db.commit()
    db.refresh(post)
    return post


@router.get("", response_model=list[PostOut])
def list_posts(
    limit: int = 100,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    """The news feed is shared by every member of every institution, newest first."""
    return db.query(Post).order_by(Post.created_at.desc()).limit(min(max(limit, 1), 200)).all()


@router.delete("/{post_id}", status_code=status.HTTP_204_NO_CONTENT)
@limiter.limit("20/minute")
def delete_post(post_id: int, request: Request, db: Session = Depends(get_db), current_user: User = Depends(require_institution_scope)):
    post = db.query(Post).filter(Post.id == post_id).first()
    if not post:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Post not found")
    if post.institution_id != current_user.institution_id and current_user.role != UserRole.SUPER_ADMIN:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Not permitted to delete this post")
    if post.author_id != current_user.id and current_user.role != UserRole.SUPER_ADMIN:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Only the author or a super admin can delete this post")
    image_path = post.image_path
    db.delete(post)
    db.commit()
    delete_storage_object(image_path)


@router.post("/{post_id}/report", response_model=PostReportOut, status_code=status.HTTP_201_CREATED)
@limiter.limit("10/minute")
def report_post(
    post_id: int,
    request: Request,
    payload: PostReportCreate,
    db: Session = Depends(get_db),
    current_user: User = Depends(require_institution_scope),
):
    """Flags a post for the super admins to review. Available to any staff member who can see the post."""
    if current_user.role != UserRole.STAFF:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Only staff can report posts")

    post = db.query(Post).filter(Post.id == post_id).first()
    if not post:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Post not found")
    if post.author_id == current_user.id:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="You can't report your own post")

    report = PostReport(
        post_id=post.id,
        reporter_id=current_user.id,
        reason=payload.reason,
    )
    db.add(report)
    db.commit()
    db.refresh(report)
    return report


def _visible_post(db: Session, post_id: int, current_user: User) -> Post:
    post = db.query(Post).filter(Post.id == post_id).first()
    if not post:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Post not found")
    return post


@router.get("/{post_id}/comments", response_model=list[PostCommentOut])
def list_comments(post_id: int, db: Session = Depends(get_db), current_user: User = Depends(get_current_user)):
    post = _visible_post(db, post_id, current_user)
    return post.comments


@router.post("/{post_id}/comments", response_model=PostCommentOut, status_code=status.HTTP_201_CREATED)
@limiter.limit("30/minute")
def add_comment(
    post_id: int,
    request: Request,
    payload: PostCommentCreate,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    if current_user.role not in (UserRole.STAFF, UserRole.INSTITUTION_ADMIN):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Only members of the institution can comment")
    post = _visible_post(db, post_id, current_user)
    comment = PostComment(post_id=post.id, author_id=current_user.id, body=payload.body)
    db.add(comment)
    db.commit()
    db.refresh(comment)
    return comment


@router.delete("/comments/{comment_id}", status_code=status.HTTP_204_NO_CONTENT)
def delete_comment(comment_id: int, db: Session = Depends(get_db), current_user: User = Depends(get_current_user)):
    comment = db.query(PostComment).filter(PostComment.id == comment_id).first()
    if not comment:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Comment not found")
    post = comment.post
    allowed = (
        comment.author_id == current_user.id
        or current_user.role == UserRole.SUPER_ADMIN
        or (current_user.role == UserRole.INSTITUTION_ADMIN and post.institution_id == current_user.institution_id)
    )
    if not allowed:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Only the author or an admin can delete this comment")
    db.delete(comment)
    db.commit()
