// Zet tekst om naar één SVG-pad (contouren), met CoreText en een variabel lettertype.
// Gebruik: swift glyphs.swift <font.ttf> <gewicht> <tekst> -> pad op stdout, eenheden = 1000/em, basislijn y=0, y omlaag.
import CoreText
import Foundation
let a = CommandLine.arguments
let desc = CTFontManagerCreateFontDescriptorsFromURL(URL(fileURLWithPath: a[1]) as CFURL) as! [CTFontDescriptor]
let wghtTag = 0x77676874 // 'wght'
let varied = CTFontDescriptorCreateCopyWithAttributes(desc[0], [kCTFontVariationAttribute: [wghtTag: Double(a[2])!]] as CFDictionary)
let font = CTFontCreateWithFontDescriptor(varied, 1000, nil)
let attr = NSAttributedString(string: a[3], attributes: [NSAttributedString.Key(kCTFontAttributeName as String): font])
let line = CTLineCreateWithAttributedString(attr)
var out = ""
for run in CTLineGetGlyphRuns(line) as! [CTRun] {
    let n = CTRunGetGlyphCount(run)
    var glyphs = [CGGlyph](repeating: 0, count: n); var pos = [CGPoint](repeating: .zero, count: n)
    CTRunGetGlyphs(run, CFRange(location: 0, length: n), &glyphs); CTRunGetPositions(run, CFRange(location: 0, length: n), &pos)
    for i in 0..<n {
        guard let path = CTFontCreatePathForGlyph(font, glyphs[i], nil) else { continue }
        let dx = pos[i].x
        path.applyWithBlock { el in
            let e = el.pointee
            func p(_ k: Int) -> String { let pt = e.points[k]; return String(format: "%.1f %.1f", pt.x + dx, -pt.y) }
            switch e.type {
            case .moveToPoint: out += "M" + p(0)
            case .addLineToPoint: out += "L" + p(0)
            case .addQuadCurveToPoint: out += "Q" + p(0) + " " + p(1)
            case .addCurveToPoint: out += "C" + p(0) + " " + p(1) + " " + p(2)
            case .closeSubpath: out += "Z"
            @unknown default: break
            }
        }
    }
}
let bounds = CTLineGetBoundsWithOptions(line, .useGlyphPathBounds)
print(String(format: "%.1f %.1f %.1f %.1f", bounds.minX, -bounds.maxY, bounds.width, bounds.height))
print(out)
