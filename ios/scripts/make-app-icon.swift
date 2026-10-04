// Maakt het app-icoon (1024x1024) uit het Family7-beeldmerk van de Android-repo:
// het beeldmerk gecentreerd op het donkerblauw van de app. Eenmalig gebruikt;
// bewaard zodat het icoon reproduceerbaar is.
// Gebruik: swift scripts/make-app-icon.swift <family7_mark.svg> <uitvoer.png>
import AppKit

let args = CommandLine.arguments
guard args.count == 3, let mark = NSImage(contentsOfFile: args[1]) else {
    fatalError("Gebruik: make-app-icon.swift <svg> <png>")
}
let size = 1024
// Geen alfakanaal: de App Store weigert iconen met transparantie.
let cg = CGContext(data: nil, width: size, height: size, bitsPerComponent: 8, bytesPerRow: 0,
                   space: CGColorSpace(name: CGColorSpace.sRGB)!,
                   bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)!
NSGraphicsContext.saveGraphicsState()
NSGraphicsContext.current = NSGraphicsContext(cgContext: cg, flipped: false)

// Verloop van #03326C naar #031A38, zoals het splashscherm van de apps.
let gradient = NSGradient(starting: NSColor(srgbRed: 0x03/255, green: 0x32/255, blue: 0x6C/255, alpha: 1),
                          ending: NSColor(srgbRed: 0x03/255, green: 0x1A/255, blue: 0x38/255, alpha: 1))!
gradient.draw(in: NSRect(x: 0, y: 0, width: size, height: size), angle: -90)

let width = CGFloat(size) * 0.70
let height = width * 326 / 360
mark.draw(in: NSRect(x: (CGFloat(size) - width) / 2, y: (CGFloat(size) - height) / 2, width: width, height: height))

NSGraphicsContext.restoreGraphicsState()
let png = NSBitmapImageRep(cgImage: cg.makeImage()!).representation(using: .png, properties: [:])!
try! png.write(to: URL(fileURLWithPath: args[2]))
