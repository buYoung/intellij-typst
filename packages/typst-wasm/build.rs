fn main() {
    println!("cargo:rustc-check-cfg=cfg(typst_v14)");
    println!("cargo:rustc-check-cfg=cfg(typst_v15)");
    let version = std::env::var("CARGO_PKG_VERSION").unwrap();
    let minor = version.split('.').nth(1).unwrap().parse::<u32>().unwrap();
    if minor >= 14 {
        println!("cargo:rustc-cfg=typst_v14");
    }
    if minor >= 15 {
        println!("cargo:rustc-cfg=typst_v15");
    }
}
