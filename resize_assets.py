import os
from PIL import Image

def resize_image(input_path, output_path, size):
    if not os.path.exists(input_path):
        print(f"Error: {input_path} not found.")
        return
    
    try:
        img = Image.open(input_path)
        img = img.resize(size, Image.Resampling.LANCZOS)
        
        os.makedirs(os.path.dirname(output_path), exist_ok=True)
        img.save(output_path)
        print(f"Saved {output_path} ({size[0]}x{size[1]})")
    except Exception as e:
        print(f"Failed to resize {input_path}: {e}")

def process_image(img_path, res_dir, icon_sizes, banner_size):
    try:
        img = Image.open(img_path)
        width, height = img.size
        ratio = width / height
        
        print(f"Processing {img_path}: {width}x{height} (Ratio: {ratio:.2f})")
        
        if 0.9 < ratio < 1.1:
            # Square - It's an Icon
            print("Detected Icon")
            for folder, size in icon_sizes.items():
                resize_image(img_path, os.path.join(res_dir, folder, "ic_launcher.png"), size)
                resize_image(img_path, os.path.join(res_dir, folder, "ic_launcher_round.png"), size)
        elif ratio > 1.5:
            # Landscape - It's a Banner
            print("Detected Banner")
            resize_image(img_path, os.path.join(res_dir, "drawable-xhdpi", "banner.png"), banner_size)
            
    except Exception as e:
        print(f"Error processing {img_path}: {e}")

def main():
    project_root = r"c:/Users/SHINE/Desktop/My Projects/Telegram Player"
    res_dir = os.path.join(project_root, "app/src/main/res")
    artifacts_dir = r"C:\Users\SHINE\.gemini\antigravity\brain\7a1d186c-5362-4f66-85eb-f1c4f22371cc"
    
    # Candidate files from artifacts
    candidates = [
        "media__1770377629966.jpg",
        "media__1770377629966.png"
    ]
    
    # Icon Sizes (Mipmap)
    icon_sizes = {
        "mipmap-mdpi": (48, 48),
        "mipmap-hdpi": (72, 72),
        "mipmap-xhdpi": (96, 96),
        "mipmap-xxhdpi": (144, 144),
        "mipmap-xxxhdpi": (192, 192)
    }
    
    # Banner Size
    banner_size = (320, 180)
    
    for filename in candidates:
        path = os.path.join(artifacts_dir, filename)
        if os.path.exists(path):
            process_image(path, res_dir, icon_sizes, banner_size)
        else:
            print(f"File not found: {path}")

if __name__ == "__main__":
    main()
