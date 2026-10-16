import io
import uuid

import httpx
from fastapi import HTTPException, UploadFile, status
from PIL import Image, ImageOps

from app.core.config import settings

ALLOWED_CONTENT_TYPES = {"image/png", "image/jpeg", "image/jpg", "image/webp"}


def _validate_content_type(file: UploadFile) -> None:
    if file.content_type not in ALLOWED_CONTENT_TYPES:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="File must be a PNG, JPEG, or WEBP image",
        )


def _process_and_upload(contents: bytes, folder: str, max_bytes: int, max_dim: tuple[int, int], photo: bool = False) -> str:
    """
    Validate size/content and re-encode with Pillow (rather than trusting
    the uploaded bytes) so a mismatched/malicious file can't slip through
    under an image content-type. Uploads the result to Supabase Storage
    (Render's local disk gets wiped on every restart) and returns the
    public URL to store on the record.
    """
    if len(contents) > max_bytes:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"Image must be under {max_bytes // (1024 * 1024)}MB",
        )

    try:
        image = Image.open(io.BytesIO(contents))
        image.verify()
        # Re-open after verify() (which leaves the file unusable for further ops)
        image = ImageOps.exif_transpose(Image.open(io.BytesIO(contents))).convert("RGBA")
    except Exception:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Uploaded file is not a valid image",
        )

    image.thumbnail(max_dim)

    buf = io.BytesIO()
    if photo:
        # Photos are stored as compressed JPEG (about a fifth of the size of PNG); transparency is flattened onto white
        flat = Image.new("RGB", image.size, (255, 255, 255))
        flat.paste(image, mask=image.split()[3])
        flat.save(buf, format="JPEG", quality=78, optimize=True, progressive=True)
        extension, content_type = "jpg", "image/jpeg"
    else:
        image.save(buf, format="PNG")
        extension, content_type = "png", "image/png"
    png_bytes = buf.getvalue()

    if not settings.SUPABASE_URL or not settings.SUPABASE_SERVICE_ROLE_KEY:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail="Image storage is not configured",
        )

    object_path = f"{folder}/{uuid.uuid4().hex}.{extension}"
    upload_url = f"{settings.SUPABASE_URL}/storage/v1/object/{settings.SUPABASE_STORAGE_BUCKET}/{object_path}"
    try:
        res = httpx.post(
            upload_url,
            content=png_bytes,
            headers={
                "Authorization": f"Bearer {settings.SUPABASE_SERVICE_ROLE_KEY}",
                "apikey": settings.SUPABASE_SERVICE_ROLE_KEY,
                "Content-Type": content_type,
                "x-upsert": "true",
            },
            timeout=20,
        )
        res.raise_for_status()
    except httpx.HTTPError:
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail="Could not upload image to storage",
        )

    return f"{settings.SUPABASE_URL}/storage/v1/object/public/{settings.SUPABASE_STORAGE_BUCKET}/{object_path}"


def delete_storage_object(public_url: str | None) -> None:
    """
    Best-effort cleanup for an old image (replaced or its record deleted).
    Silently no-ops on anything that isn't one of our own Storage URLs
    (e.g. leftover local-disk paths from before this migration) so it's
    always safe to call without checking the value's shape first. Never
    raises — losing an orphaned file is a minor cost; failing the actual
    delete/update the user asked for over a cleanup step is not worth it.
    """
    if not public_url or not settings.SUPABASE_URL or not settings.SUPABASE_SERVICE_ROLE_KEY:
        return
    prefix = f"{settings.SUPABASE_URL}/storage/v1/object/public/{settings.SUPABASE_STORAGE_BUCKET}/"
    if not public_url.startswith(prefix):
        return
    object_path = public_url[len(prefix):]
    delete_url = f"{settings.SUPABASE_URL}/storage/v1/object/{settings.SUPABASE_STORAGE_BUCKET}/{object_path}"
    try:
        httpx.delete(
            delete_url,
            headers={
                "Authorization": f"Bearer {settings.SUPABASE_SERVICE_ROLE_KEY}",
                "apikey": settings.SUPABASE_SERVICE_ROLE_KEY,
            },
            timeout=10,
        )
    except httpx.HTTPError:
        pass


def save_school_logo(file: UploadFile, contents: bytes) -> str:
    _validate_content_type(file)
    return _process_and_upload(contents, "logos", settings.MAX_LOGO_SIZE_BYTES, (1024, 1024))


def save_transaction_image(file: UploadFile, contents: bytes) -> str:
    """Optional evidence photo attached to a Receiving/Paying entry."""
    _validate_content_type(file)
    return _process_and_upload(contents, "transactions", settings.MAX_TRANSACTION_IMAGE_SIZE_BYTES, (1600, 1600), photo=True)


def save_post_image(file: UploadFile, contents: bytes) -> str:
    """Optional image attached to a news post."""
    _validate_content_type(file)
    return _process_and_upload(contents, "posts", settings.MAX_POST_IMAGE_SIZE_BYTES, (1600, 1600), photo=True)


def save_avatar(file: UploadFile, contents: bytes) -> str:
    """A member's profile photo, shown beside their news posts."""
    _validate_content_type(file)
    return _process_and_upload(contents, "avatars", settings.MAX_LOGO_SIZE_BYTES, (512, 512))
