import sys
import argparse
from PIL import Image

def process_sprite(input_path, output_path, target_max_dim=100, bg_color_tolerance=10):
    try:
        img = Image.open(input_path).convert("RGBA")
    except Exception as e:
        print(f"Error loading image: {e}")
        return

    data = img.getdata()
    new_data = []

    # Get the background color from the top-left pixel (assuming it's background)
    bg_color = data[0]
    
    # We will use flood fill to separate background from foreground in a better way,
    # but a simple color distance metric works okay for perfectly solid backgrounds.
    # To handle anti-aliasing better, let's just make the exact gray transparent.
    # Actually, let's just use a simple Euclidean distance for transparency.
    for item in data:
        # item is (R, G, B, A)
        r_diff = abs(item[0] - bg_color[0])
        g_diff = abs(item[1] - bg_color[1])
        b_diff = abs(item[2] - bg_color[2])
        
        # If it's extremely close to the background grey
        if r_diff < bg_color_tolerance and g_diff < bg_color_tolerance and b_diff < bg_color_tolerance:
            new_data.append((item[0], item[1], item[2], 0))  # Fully transparent
        else:
            new_data.append(item)

    img.putdata(new_data)

    # Crop the image to the bounding box of non-transparent pixels
    bbox = img.getbbox()
    if bbox:
        img = img.crop(bbox)

    # Resize the image
    width, height = img.size
    scaling_factor = target_max_dim / max(width, height)
    new_width = int(width * scaling_factor)
    new_height = int(height * scaling_factor)

    # Use LANCZOS for high-quality downsampling
    img = img.resize((new_width, new_height), Image.Resampling.LANCZOS)

    img.save(output_path, "PNG")
    print(f"Successfully processed sprite! Saved to {output_path}")
    print(f"New dimensions: {new_width}x{new_height} pixels")
    print(f"Recommended center for .ship file: [{new_width//2}, {new_height//2}]")

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Process ship sprite for Starsector.")
    parser.add_argument("input", help="Path to input image (e.g., raw_image.jpg)")
    parser.add_argument("--output", default="contents/graphics/ships/astd_arc_flash.png", help="Path to output PNG")
    parser.add_argument("--size", type=int, default=100, help="Target max dimension (width or height)")
    
    args = parser.parse_args()
    process_sprite(args.input, args.output, args.size)
