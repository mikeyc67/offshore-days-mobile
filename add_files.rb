# Adds our Swift files to the Xcode project that `npx cap add ios` generates.
require 'xcodeproj'

project = Xcodeproj::Project.open('ios/App/App.xcodeproj')
target = project.targets.find { |t| t.name == 'App' }
group = project.main_group.find_subpath('App', false)
%w[FenceManager.swift ShipFencePlugin.swift MainViewController.swift].each do |name|
  next if group.files.any? { |f| f.path == name }
  ref = group.new_reference(name)
  target.source_build_phase.add_file_reference(ref, true)
end
project.save
puts 'Added Swift files to the App target.'
